# =============================================================
#  openapi.json 导出脚本（契约冻结的唯一入口）
#
#  为什么不用 springdoc-openapi-maven-plugin（CR-002）：
#    项目原方案把它绑在 integration-test 阶段，并给的命令是 `mvn package` ——
#    Maven 生命周期里 integration-test 在 package **之后**，所以插件永远不会执行；
#    而且 pom 里没有配 spring-boot:start/stop，没有应用在跑，抓不到 /v3/api-docs。
#    本脚本改用「打包 → 起 jar → curl → 停」的显式流程，与 CI 的做法一致，
#    依赖更少、失败原因更明确。
#
#  ⚠️ 编码要求：本文件必须保存为 UTF-8 with BOM（PowerShell 5.1 + GBK 代码页）。
#
#  用法：
#    powershell -File scripts/export_openapi.ps1
#    powershell -File scripts/export_openapi.ps1 -SkipBuild      # 复用已有 jar
#    powershell -File scripts/export_openapi.ps1 -KeepRunning    # 导出后不关应用（调试用）
#
#  完成后：由 **L1** 审核并提交 openapi.json，其他人不得手工编辑它
#          （见 docs/agents/工作计划.md §2 所有权表）。
# =============================================================

[CmdletBinding()]
param(
    [int]    $Port        = 8080,
    [string] $JarPath,                 # 缺省自动找 server/target/*.jar
    [string] $OutFile,                 # 缺省写仓库根 openapi.json
    [int]    $StartTimeoutSec = 120,   # 应用启动 + 端点就绪的等待上限
    [switch] $SkipBuild,
    [switch] $KeepRunning
)

$ErrorActionPreference = 'Continue'   # 同其他脚本：原生命令的 stderr 不应终止脚本

$repoRoot = Split-Path $PSScriptRoot -Parent
$serverDir = Join-Path $repoRoot 'server'
if (-not $OutFile) { $OutFile = Join-Path $repoRoot 'openapi.json' }

# ---------- 0. 环境前置 ----------
Write-Host ''
Write-Host '=== 导出 openapi.json ===' -ForegroundColor Cyan

$javaHome = 'D:\develop\jdk21'
if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    Write-Host "  [FAIL] 找不到 JDK 21：$javaHome" -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome 'bin') + ';' + $env:PATH
# 关键：已运行的宿主进程仍持旧环境，子进程会继承它 —— 必须显式覆盖，否则会退回 jdk17
Write-Host ('  JAVA_HOME = {0}' -f $env:JAVA_HOME) -ForegroundColor DarkGray

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) { Write-Host '  [FAIL] 找不到 mvn' -ForegroundColor Red; exit 1 }

# 依赖服务探活（起不来应用时，先排除"服务没开"这种低级原因）
function Test-TcpPort([string]$h, [int]$p) {
    try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect($h, $p); $c.Close(); return $true }
    catch { return $false }
}
$mysqlOk = Test-TcpPort '127.0.0.1' 3306
$redisOk = Test-TcpPort '192.168.100.128' 6380
Write-Host ('  依赖探活: MySQL 127.0.0.1:3306 = {0} ; Redis 192.168.100.128:6380 = {1}' -f $mysqlOk, $redisOk) -ForegroundColor DarkGray
if (-not $mysqlOk -or -not $redisOk) {
    Write-Host '  [FAIL] 依赖服务未就绪 —— 应用起不来就不是契约的问题。先修环境（见 docs/ops/deployment.md §3）。' -ForegroundColor Red
    exit 1
}

# ---------- 0.5 端口占用检查（**必须放在打包之前**）----------
# 为什么必须拦：本脚本的就绪探针只判断 /v3/api-docs 是否返回 200，
# 它**分不清"我起的应用"和"别人跑着的旧实例"**。若端口已被占用：
#   ① 本脚本起的应用会因端口冲突而退出（或退出得慢）；
#   ② 在它退出之前，探针会先探到**那个旧实例** → 200 → 判定就绪；
#   ③ 于是脚本把**旧版本的契约**写进 openapi.json，并打印 [PASS]。
# 这是最坏的一类失败：产物错了，而日志是绿的。所以宁可在启动前显式失败。
$portBusy = $false
try {
    $probe = New-Object System.Net.Sockets.TcpClient
    $probe.Connect('127.0.0.1', $Port)
    $probe.Close()
    $portBusy = $true
} catch { $portBusy = $false }
if ($portBusy) {
    Write-Host ('  [FAIL] 端口 {0} 已被占用，先停掉占用者再导出。' -f $Port) -ForegroundColor Red
    Write-Host '         必须停掉的原因：本脚本的就绪探针只看 /v3/api-docs 是否 200，' -ForegroundColor Yellow
    Write-Host '         它分不清"我起的应用"与"别人跑着的旧实例"，会把旧契约写成新契约。' -ForegroundColor Yellow
    $who = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    foreach ($x in $who) {
        $pr = Get-Process -Id $x.OwningProcess -ErrorAction SilentlyContinue
        $pname = '?'
        # 不用内联 if：PowerShell 5.1 只在语句上下文里允许 if，表达式里会解析失败（见 §5 坑 1）
        if ($pr) { $pname = $pr.ProcessName }
        Write-Host ('         占用者: pid={0} name={1}' -f $x.OwningProcess, $pname) -ForegroundColor Yellow
    }
    exit 1
}

# ---------- 0.6 fail-fast 的凭据：导出契约只需要应用**能启动** ----------
# 2026-09-16 起 media 包的凭据属性带 @NotBlank（裁决 ② 兑现的 fail-fast）：
# 不设 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET，应用**启动即失败**（实测 exit 1）。
# 而本脚本只抓 /v3/api-docs —— **不需要真实凭据**，也从不调用 OSS。
# 所以在"未设置"时补两个**自曝身份的占位值**，让导出不被一个与契约无关的原因挡住。
#   ⚠️ 这两个值不能用于任何上传；它们只活在本进程的环境变量里，不落盘、不进仓库。
#   ⚠️ 若你已经设了真实值，本脚本**不会覆盖**（只在未设置时补）。
foreach ($credKey in 'OSS_ACCESS_KEY_ID', 'OSS_ACCESS_KEY_SECRET') {
    if (-not [Environment]::GetEnvironmentVariable($credKey, 'Process')) {
        [Environment]::SetEnvironmentVariable($credKey, 'export-openapi-placeholder-not-a-real-key', 'Process')
        Write-Host ('  [i] {0} 未设置 → 本次导出使用占位值（导出不需要真实凭据）' -f $credKey) -ForegroundColor DarkGray
    }
}

# ---------- 1. 打包 ----------
if (-not $SkipBuild) {
    Write-Host '  [1/5] mvn package ...' -ForegroundColor Cyan
    Push-Location $serverDir
    $build = & mvn -B --no-transfer-progress -DskipTests package 2>&1
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) {
        Write-Host '  [FAIL] 打包失败，末 25 行：' -ForegroundColor Red
        $build | Select-Object -Last 25 | ForEach-Object { '         ' + $_.ToString() }
        exit 1
    }
    Write-Host '        [PASS] 打包成功' -ForegroundColor Green
} else {
    Write-Host '  [1/5] 跳过打包（-SkipBuild）' -ForegroundColor DarkGray
}

# ---------- 2. 定位可执行 jar ----------
if (-not $JarPath) {
    $cand = Get-ChildItem (Join-Path $serverDir 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '\.original$|sources|javadoc' } |
            Sort-Object LastWriteTime -Descending
    if (-not $cand) { Write-Host '  [FAIL] target 下找不到可执行 jar' -ForegroundColor Red; exit 1 }
    $JarPath = $cand[0].FullName
}
Write-Host ('  [2/5] jar = {0}' -f $JarPath) -ForegroundColor Cyan

# ---------- 3. 启动应用 ----------
$logOut = Join-Path $repoRoot '.tmp\export-openapi.out.log'
$logErr = Join-Path $repoRoot '.tmp\export-openapi.err.log'
$tmpDir = Split-Path $logOut -Parent
if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null }
Remove-Item $logOut, $logErr -Force -ErrorAction SilentlyContinue

Write-Host '  [3/5] 启动应用 ...' -ForegroundColor Cyan
$javaExe = Join-Path $javaHome 'bin\java.exe'
# '-Dstdout.encoding=UTF-8'：Windows 控制台默认 GBK，不看这个参数中文日志全是乱码
$proc = Start-Process -FilePath $javaExe `
    -ArgumentList '-Dstdout.encoding=UTF-8', '-Dfile.encoding=UTF-8', '-jar', $JarPath `
    -RedirectStandardOutput $logOut -RedirectStandardError $logErr `
    -WindowStyle Hidden -PassThru
Write-Host ('        pid = {0}' -f $proc.Id) -ForegroundColor DarkGray

$url = "http://127.0.0.1:$Port/v3/api-docs"
$ok = $false
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $StartTimeoutSec) {
    if ($proc.HasExited) {
        Write-Host ('  [FAIL] 应用提前退出（exit={0}）。日志末 25 行：' -f $proc.ExitCode) -ForegroundColor Red
        Get-Content $logOut -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { '         ' + $_ }
        Get-Content $logErr -Tail 10 -ErrorAction SilentlyContinue | ForEach-Object { '         ' + $_ }
        exit 1
    }
    try {
        $r = Invoke-WebRequest -Uri $url -TimeoutSec 5 -UseBasicParsing
        if ($r.StatusCode -eq 200 -and $r.Content.Length -gt 50) { $ok = $true; break }
    } catch { Start-Sleep -Milliseconds 1500 }
}
if (-not $ok) {
    Write-Host ('  [FAIL] {0} 秒内 /v3/api-docs 未就绪' -f $StartTimeoutSec) -ForegroundColor Red
    Get-Content $logOut -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { '         ' + $_ }
    if (-not $KeepRunning) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    exit 1
}
Write-Host ('        [PASS] 端点就绪，用时 {0:N1}s' -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green

# ---------- 4. 抓取并写入 ----------
Write-Host '  [4/5] 抓取并写入契约 ...' -ForegroundColor Cyan
$json = $r.Content
# 校验：必须是合法 JSON，且含本项目应有的路径（防止抓到错误页）
try { $obj = $json | ConvertFrom-Json } catch {
    Write-Host '  [FAIL] 返回内容不是合法 JSON' -ForegroundColor Red; exit 1
}
$paths = @($obj.paths.PSObject.Properties.Name)
if ($paths.Count -eq 0) { Write-Host '  [FAIL] 契约里没有任何 path' -ForegroundColor Red; exit 1 }

$mustHave = @('/api/auth/register', '/api/auth/login', '/api/user/me')
$missing = $mustHave | Where-Object { $paths -notcontains $_ }
# 统一 LF + 末尾换行，避免 git 噪音
$normalized = ($json -replace "`r`n", "`n").TrimEnd() + "`n"
[System.IO.File]::WriteAllText($OutFile, $normalized, (New-Object System.Text.UTF8Encoding($false)))

$sha = (Get-FileHash $OutFile -Algorithm SHA256).Hash
Write-Host ('        写入 {0}' -f $OutFile) -ForegroundColor Green
Write-Host ('        路径数 = {0} ; 大小 = {1:N1} KB' -f $paths.Count, ((Get-Item $OutFile).Length / 1KB)) -ForegroundColor Green
Write-Host ('        SHA256 = {0}' -f $sha) -ForegroundColor Green
if ($missing.Count -gt 0) {
    Write-Host ('        [WARN] 契约缺少 M1 应有的路径: {0}' -f ($missing -join ', ')) -ForegroundColor Yellow
    Write-Host '               （若 M1 尚未实现完，属预期；否则说明路由没注册上）' -ForegroundColor DarkGray
}

# ---------- 5. 停应用 ----------
Write-Host '  [5/5] 收尾 ...' -ForegroundColor Cyan
if ($KeepRunning) {
    Write-Host ('        [SKIP] 应用仍在运行（-KeepRunning），pid={0}；用完请自行 Stop-Process' -f $proc.Id) -ForegroundColor Yellow
} else {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Write-Host '        已停止应用' -ForegroundColor Green
}

Write-Host ''
Write-Host '=== 完成。下一步（L1）：审核契约 → 提交 → 在工作计划.md §4 广播冻结版本与 hash ===' -ForegroundColor Cyan
exit 0
