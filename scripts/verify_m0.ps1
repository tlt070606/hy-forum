# =============================================================
#  M0 验收脚本 —— 数据库地基
#
#  ⚠️ 文件编码要求：本文件**必须保存为 UTF-8 with BOM**。
#     原因：本机只有 Windows PowerShell 5.1（无 pwsh 7），系统 ANSI 代码页为 936(GBK)，
#     PowerShell 5.1 会把**没有 BOM** 的 .ps1 按 GBK 解读，导致中文变成乱码并使解析失败
#     （实测：无 BOM 时 19 处语法错误）。带 BOM 后 5.1 与 pwsh 7 都能正确解析。
#     同理：.sql 文件**不要加 BOM**（mysql 客户端不期望 BOM，且默认按 utf8mb4 读取）。
#
#  用法：
#    # 全新库：建库 + 建表 + 灌初始数据 + 校验
#    powershell -File scripts/verify_m0.ps1 -User root -Password '你的密码' -Init
#
#    # 只校验（库已存在）
#    powershell -File scripts/verify_m0.ps1 -User root -Password '你的密码'
#
#  设计说明：
#   * 这是 M0 的「完成定义」—— 没有这条命令的输出，M0 不算完成。
#   * 密码不放在命令行里（避免进入进程列表），改用 MYSQL_PWD 环境变量传递给 mysql 客户端。
#   * 所有检查都直接查 information_schema，不靠"肉眼看文档"。
# =============================================================

[CmdletBinding()]
param(
    [string]$MySqlPath,                       # mysql.exe 路径，缺省自动探测
    [string]$DbHost   = '127.0.0.1',
    [int]   $Port     = 3306,
    [string]$User     = 'root',
    [string]$Password,
    [switch]$Init,                            # 全新库时使用：执行 schema.sql + seed.sql
    [string]$SchemaPath,
    [string]$SeedPath
)

# 注意：这里**不能**用 'Stop'。
# 本脚本第 [2] 节会"故意"执行一条应被 CHECK 约束拒绝的 INSERT，mysql 会往 stderr 写错误。
# 在 Windows PowerShell 5.1 下，原生命令的 stderr 经 2>&1 重定向后变成 ErrorRecord，
# 若 ErrorActionPreference 为 Stop 会抛出 NativeCommandError 终止脚本 ——
# 那恰好会把本脚本最关键的这条验证打挂。因此改为 Continue，并一律用 $LASTEXITCODE 判定。
$ErrorActionPreference = 'Continue'
$script:Fail = 0
$script:Warn = 0

# ---------- 路径解析 ----------
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $SchemaPath) { $SchemaPath = Join-Path $repoRoot 'docs\db\schema.sql' }
if (-not $SeedPath)   { $SeedPath   = Join-Path $repoRoot 'docs\db\seed.sql' }

if (-not $MySqlPath) {
    $cmd = Get-Command mysql -ErrorAction SilentlyContinue
    if ($cmd) { $MySqlPath = $cmd.Source }
    elseif (Test-Path 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe') {
        $MySqlPath = 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe'
    } else { throw "找不到 mysql.exe，请用 -MySqlPath 指定" }
}
foreach ($f in @($SchemaPath, $SeedPath)) {
    if (-not (Test-Path $f)) { throw "文件不存在：$f" }
}

if ($Password) { $env:MYSQL_PWD = $Password }
$commonArgs = @('--default-character-set=utf8mb4', '-h', $DbHost, '-P', $Port, '-u', $User, '-N', '-B')

# ---------- 工具函数 ----------
function Invoke-Sql {
    <#  执行一条 SQL，返回 @{ Out; Err; Code }  #>
    param([string]$Sql)
    $out = & $MySqlPath @commonArgs -e $Sql 2>&1
    return @{ Out = ($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) -join "`n"
              Err = ($out | Where-Object { $_ -is  [System.Management.Automation.ErrorRecord] }) -join "`n"
              Code = $LASTEXITCODE }
}

function Assert-Eq {
    param([string]$Name, $Actual, $Expected, [string]$Hint = '')
    $Actual = "$Actual".Trim()
    if ($Actual -eq "$Expected") {
        Write-Host ("  [PASS] {0,-46} = {1}" -f $Name, $Actual) -ForegroundColor Green
    } else {
        $script:Fail++
        Write-Host ("  [FAIL] {0,-46} 期望 {1}，实际 {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
        if ($Hint) { Write-Host ("         → " + $Hint) -ForegroundColor DarkGray }
    }
}

function Assert-True {
    param([string]$Name, [bool]$Ok, [string]$Detail = '', [string]$Hint = '')
    if ($Ok) { Write-Host ("  [PASS] {0,-46} {1}" -f $Name, $Detail) -ForegroundColor Green }
    else {
        $script:Fail++
        Write-Host ("  [FAIL] {0,-46} {1}" -f $Name, $Detail) -ForegroundColor Red
        if ($Hint) { Write-Host ("         → " + $Hint) -ForegroundColor DarkGray }
    }
}

function Add-Warn {
    param([string]$Msg)
    $script:Warn++
    Write-Host ("  [WARN] " + $Msg) -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== Hy论坛 M0 验收（数据库地基） ===" -ForegroundColor Cyan
Write-Host ("mysql   : {0}" -f $MySqlPath)
Write-Host ("target  : {0}:{1} as {2}" -f $DbHost, $Port, $User)
Write-Host ""

# ---------- 0. 连通性与版本 ----------
Write-Host "[0] 连通性与版本" -ForegroundColor Cyan
$r = Invoke-Sql 'SELECT VERSION();'
if ($r.Code -ne 0) {
    Write-Host ("  [FAIL] 无法连接 MySQL： " + $r.Err) -ForegroundColor Red
    Write-Host "         → 检查账号密码；若未初始化数据库，请加 -Init" -ForegroundColor DarkGray
    exit 1
}
$ver = $r.Out.Trim()
Write-Host ("  [PASS] 连接成功，版本 {0}" -f $ver) -ForegroundColor Green

$vParts = $ver.Split('.')
$vNum = [int]$vParts[0] * 10000 + [int]$vParts[1] * 100 + [int]($vParts[2] -replace '\D.*$','')
Assert-True "MySQL >= 8.0.16（CHECK 约束才真正强制执行）" ($vNum -ge 80016) "当前 $ver" `
            "低于 8.0.16 时 CHECK 只解析不生效，两层结构不变量形同虚设"

# ---------- 可选：初始化 ----------
if ($Init) {
    Write-Host ""
    Write-Host "[I] 执行 schema.sql + seed.sql（全新库）" -ForegroundColor Cyan
    foreach ($pair in @(@('schema.sql', $SchemaPath), @('seed.sql', $SeedPath))) {
        $name = $pair[0]; $path = $pair[1]
        $cmdLine = "`"$MySqlPath`" --default-character-set=utf8mb4 -h $DbHost -P $Port -u $User < `"$path`""
        $o = & cmd.exe /c $cmdLine 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("  [FAIL] {0} 执行失败：" -f $name) -ForegroundColor Red
            Write-Host ("         " + ($o -join "`n")) -ForegroundColor Red
            exit 1
        }
        Write-Host ("  [PASS] {0} 执行成功（0 错误）" -f $name) -ForegroundColor Green
    }
}

# ---------- 1. 库与表 ----------
Write-Host ""
Write-Host "[1] 库与表结构" -ForegroundColor Cyan
$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='hy_forum';"
Assert-Eq "数据库 hy_forum 存在" $r.Out 1 "未创建时请加 -Init"

$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='hy_forum' AND TABLE_TYPE='BASE TABLE';"
Assert-Eq "表数量" $r.Out 16 "以 docs/db/schema.sql 声明的 16 张为准"

$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='hy_forum' AND TABLE_TYPE='BASE TABLE' AND (ENGINE<>'InnoDB' OR TABLE_COLLATION NOT LIKE 'utf8mb4%');"
Assert-Eq "非 InnoDB / 非 utf8mb4 的表" $r.Out 0 "db/README.md 基线要求全部 InnoDB + utf8mb4"

$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='hy_forum' AND CONSTRAINT_TYPE='FOREIGN KEY';"
Assert-Eq "外键约束数量" $r.Out 0 "db/README.md 明确「不使用外键约束」"

$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='hy_forum' AND COLUMN_KEY='PRI' AND LOWER(COLUMN_TYPE)<>'bigint unsigned';"
Assert-Eq "主键类型不统一的列" $r.Out 0 "规范：主键统一 BIGINT UNSIGNED AUTO_INCREMENT"

$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='hy_forum' AND INDEX_NAME<>'PRIMARY' AND INDEX_NAME NOT REGEXP '^(uk_|idx_|ft_)';"
Assert-Eq "索引命名不规范的数量" $r.Out 0 "规范：uk_ / idx_ / ft_"

# ---------- 2. 两层结构不变量（P1-3 定案） ----------
Write-Host ""
Write-Host "[2] 评论两层结构不变量（P1-3）" -ForegroundColor Cyan
$r = Invoke-Sql "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='hy_forum' AND TABLE_NAME='comment' AND CONSTRAINT_NAME='chk_comment_two_levels' AND CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES';"
Assert-Eq "CHECK 约束 chk_comment_two_levels 存在且强制" $r.Out 1 `
          "缺它则「杜绝三层」只剩应用层约定"

# 关键：**双向**验证 —— 既证明它"会拦非法"，也证明它"不拦合法"。
# 只测拒绝是不够的：一个写成 CHECK(1=0) 的错误约束同样能通过"拒绝测试"，
# 却会让所有合法写入失败。这是本脚本第一版自身的盲区，已修。
$cases = @(
    @{ n = '主楼 (0,0)';          expect = 0; sql = "INSERT INTO hy_forum.comment (post_id,user_id,parent_id,root_id,content) VALUES (999999,999999,0,0,'m0-verify');" },
    @{ n = '楼中楼 (5,5)';        expect = 0; sql = "INSERT INTO hy_forum.comment (post_id,user_id,parent_id,root_id,content) VALUES (999999,999999,5,5,'m0-verify');" },
    @{ n = 'parent<>0 且 root=0'; expect = 1; sql = "INSERT INTO hy_forum.comment (post_id,user_id,parent_id,root_id,content) VALUES (999999,999999,5,0,'m0-verify');" },
    @{ n = 'parent=0 且 root<>0'; expect = 1; sql = "INSERT INTO hy_forum.comment (post_id,user_id,parent_id,root_id,content) VALUES (999999,999999,0,7,'m0-verify');" },
    @{ n = 'parent<>root 均非 0';  expect = 1; sql = "INSERT INTO hy_forum.comment (post_id,user_id,parent_id,root_id,content) VALUES (999999,999999,3,9,'m0-verify');" }
)
foreach ($c in $cases) {
    $rr = Invoke-Sql $c.sql
    $ok = if ($c.expect -eq 0) { $rr.Code -eq 0 } else { $rr.Code -ne 0 }
    if ($c.expect -eq 0) {
        $hint = "合法数据被拒 → 约束写错了（例如 CHECK(1=0)），会阻断全部评论写入"
    } else {
        $hint = "非法数据被放行 → CHECK 未真正生效（MySQL < 8.0.16 或约束缺失）"
    }
    $label = $c.n + $(if ($c.expect -eq 0) { ' 被接受' } else { ' 被拒绝' })
    Assert-True ("CHECK " + $label) $ok ("退出码 " + $rr.Code) $hint
}
# 无论结果如何都清理，避免残留测试数据
Invoke-Sql "DELETE FROM hy_forum.comment WHERE content='m0-verify';" | Out-Null

# ---------- 3. 初始数据 ----------
Write-Host ""
Write-Host "[3] 初始数据（seed.sql）" -ForegroundColor Cyan
$r = Invoke-Sql "SELECT COUNT(*) FROM hy_forum.board;"
Assert-Eq "版块数量" $r.Out 7 "技术方案 §4.3 定义 7 个默认版块"

$r = Invoke-Sql "SELECT COUNT(*) FROM hy_forum.admin;"
Assert-True "管理员数量 >= 1" ([int]$r.Out -ge 1) ("共 " + $r.Out)

$r = Invoke-Sql "SELECT config_value FROM hy_forum.sys_config WHERE config_key='register_mode';"
Assert-True "register_mode 已配置且取值合法" (@('open','invite','closed') -contains $r.Out.Trim()) `
            ("= " + $r.Out.Trim()) "合法取值：open / invite / closed"

# ---------- 4. 需要人工处理的遗留项（WARN，不算失败） ----------
Write-Host ""
Write-Host "[4] 遗留项提醒" -ForegroundColor Cyan
$r = Invoke-Sql "SELECT COUNT(*) FROM hy_forum.admin WHERE password_hash LIKE '%PLACEHOLDER%';"
if ([int]$r.Out -gt 0) {
    Add-Warn "管理员密码仍是占位符，无法登录。需用 BCrypt(strength=10) 生成哈希后替换（M1 完成后可做）"
}
$r = Invoke-Sql "SELECT COUNT(*) FROM hy_forum.sensitive_word WHERE word LIKE '%PLACEHOLDER%' OR word LIKE '%占位词%';"
if ([int]$r.Out -gt 0) {
    Add-Warn "敏感词库仍是占位数据，上线前必须导入真实开源词库（内容安全决策，需需求方选定）"
}

# ---------- 汇总 ----------
if ($Password) { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }   # 清理凭据环境变量
Write-Host ""
if ($script:Fail -eq 0) {
    Write-Host ("=== M0 验收通过：全部检查项 PASS，{0} 条 WARN ===" -f $script:Warn) -ForegroundColor Green
    exit 0
} else {
    Write-Host ("=== M0 验收未通过：{0} 项 FAIL，{1} 条 WARN ===" -f $script:Fail, $script:Warn) -ForegroundColor Red
    exit 1
}
