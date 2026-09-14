# =============================================================
#  测试库初始化脚本 —— 每次测试前重建一个干净的 hy_forum_test
#
#  为什么需要它：本项目**不使用 Testcontainers**（不装 Docker Desktop），
#  集成测试改为连本机 MySQL 的独立测试库。代价是失去了容器级隔离，
#  因此必须靠「每次运行前把测试库重建一次」来保证起点干净。
#
#  关键设计：**DDL 从 docs/db/schema.sql 派生，不复制一份。**
#  否则测试库的表结构与生产库会各自漂移 —— 这正是项目反复强调的红线。
#
#  ⚠️ 编码要求：本文件必须保存为 UTF-8 with BOM（PowerShell 5.1 + GBK 代码页）。
#    生成的 .sql 临时文件则为 UTF-8 **无 BOM**（mysql 客户端不期望 BOM）。
#
#  用法：
#    powershell -File scripts/init_test_db.ps1 -User root -Password '***'
#    powershell -File scripts/init_test_db.ps1 -User root -Password '***' -KeepSql   # 保留生成的 SQL 以便查看
# =============================================================

[CmdletBinding()]
param(
    [string]$MySqlPath,
    [string]$DbHost   = '127.0.0.1',
    [int]   $Port     = 3306,
    [string]$User     = 'root',
    [string]$Password,
    [string]$TestDb   = 'hy_forum_test',
    [switch]$KeepSql
)

$ErrorActionPreference = 'Continue'   # 同 verify_m0.ps1：原生命令的 stderr 不应终止脚本

$repoRoot   = Split-Path $PSScriptRoot -Parent
$schemaPath = Join-Path $repoRoot 'docs\db\schema.sql'
$tmpDir     = Join-Path $repoRoot '.tmp'
$tmpSql     = Join-Path $tmpDir 'hy_forum_test.sql'

if (-not $MySqlPath) {
    $cmd = Get-Command mysql -ErrorAction SilentlyContinue
    if ($cmd) { $MySqlPath = $cmd.Source }
    elseif (Test-Path 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe') {
        $MySqlPath = 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe'
    } else { throw "找不到 mysql.exe，请用 -MySqlPath 指定" }
}
if (-not (Test-Path $schemaPath)) { throw "找不到 schema.sql：$schemaPath" }
if ($Password) { $env:MYSQL_PWD = $Password }

$commonArgs = @('--default-character-set=utf8mb4', '-h', $DbHost, '-P', $Port, '-u', $User, '-N', '-B')

function Invoke-Sql {
    param([string]$Sql)
    $out = & $MySqlPath @commonArgs -e $Sql 2>&1
    return @{ Out  = ($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) -join "`n"
              Err  = ($out | Where-Object { $_ -is  [System.Management.Automation.ErrorRecord] }) -join "`n"
              Code = $LASTEXITCODE }
}

Write-Host ""
Write-Host "=== 重建测试库 $TestDb（DDL 由 schema.sql 派生） ===" -ForegroundColor Cyan

# ---------- 1. 读取并改写 DDL ----------
$ddl = [System.IO.File]::ReadAllText($schemaPath, [System.Text.Encoding]::UTF8)

if ($ddl -notmatch 'CREATE DATABASE IF NOT EXISTS hy_forum') {
    throw "schema.sql 结构与预期不符：未找到 'CREATE DATABASE IF NOT EXISTS hy_forum'。若基线已改名，请同步更新本脚本。"
}
$occurrences = ([regex]::Matches($ddl, 'hy_forum')).Count
$ddlTest = $ddl -replace 'hy_forum', $TestDb

# 守卫：改写后必须恰好指向测试库，且不得残留生产库名
if ($ddlTest -notmatch [regex]::Escape("CREATE DATABASE IF NOT EXISTS $TestDb")) {
    throw "改写失败：未生成 CREATE DATABASE IF NOT EXISTS $TestDb"
}
if ($ddlTest -notmatch [regex]::Escape("USE $TestDb;")) {
    throw "改写失败：未生成 USE $TestDb;"
}
if ($ddlTest -match 'hy_forum(?!_test)') {
    throw "改写失败：结果中仍残留生产库名 hy_forum"
}
Write-Host ("  [PASS] DDL 改写完成（替换 {0} 处 hy_forum -> {1}）" -f $occurrences, $TestDb) -ForegroundColor Green

# ---------- 2. 每次重建：先删库，保证起点干净 ----------
$finalSql = "DROP DATABASE IF EXISTS ``$TestDb``;`r`n" + $ddlTest

if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null }
# 关键：UTF-8 **无 BOM**，否则 mysql 客户端可能把 BOM 当成 SQL 的一部分而报错
[System.IO.File]::WriteAllText($tmpSql, $finalSql, (New-Object System.Text.UTF8Encoding($false)))

# ---------- 3. 执行（字节级重定向，避免 PowerShell 管道改坏中文） ----------
$cmdLine = "`"$MySqlPath`" --default-character-set=utf8mb4 -h $DbHost -P $Port -u $User < `"$tmpSql`""
$o = & cmd.exe /c $cmdLine 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [FAIL] 执行测试库 DDL 失败：" -ForegroundColor Red
    Write-Host ("         " + ($o -join "`n")) -ForegroundColor Red
    if ($Password) { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
    exit 1
}
Write-Host "  [PASS] DROP + CREATE 执行成功" -ForegroundColor Green

# ---------- 4. 校验 ----------
$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TestDb' AND TABLE_TYPE='BASE TABLE';"
if ("$($r.Out)".Trim() -eq '16') {
    Write-Host "  [PASS] 测试库表数量 = 16" -ForegroundColor Green
} else {
    Write-Host ("  [FAIL] 测试库表数量 = {0}（期望 16）" -f $r.Out) -ForegroundColor Red
    if ($Password) { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
    exit 1
}
$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='$TestDb' AND CONSTRAINT_NAME='chk_comment_two_levels';"
if ("$($r.Out)".Trim() -eq '1') {
    Write-Host "  [PASS] 两层结构 CHECK 约束已随 DDL 复制到测试库" -ForegroundColor Green
} else {
    Write-Host "  [FAIL] 测试库缺少 chk_comment_two_levels" -ForegroundColor Red
    if ($Password) { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
    exit 1
}

# ---------- 5. 清理 ----------
if (-not $KeepSql) { Remove-Item $tmpSql -Force -ErrorAction SilentlyContinue }
else { Write-Host ("  生成的 SQL 保留在：{0}" -f $tmpSql) -ForegroundColor DarkGray }
if ($Password) { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }

Write-Host ""
Write-Host ("=== 测试库 $TestDb 就绪 ===" -f $TestDb) -ForegroundColor Green
exit 0
