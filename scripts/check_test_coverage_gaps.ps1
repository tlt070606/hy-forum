# =============================================================
#  验收项覆盖缺口检查 —— 「哪条验收项还没有对应测试方法」
#
#  为什么需要它：`docs/testing/README.md` §5 规定**测试方法名必须带验收项编号**
#  （如 `M4_like_idempotent_concurrent`），目的就是能用脚本把
#  `docs/testing/验收项-测试映射.md`（期望）与 `server/src/test`（实际）做差集。
#  这比代码覆盖率有意义：覆盖率 90% 也可能一条业务规则都没验证过。
#
#  数据来源（**只有一个事实来源，不复制任何清单**）：
#    · 期望：docs/testing/验收项-测试映射.md 的「测试方法名」列（反引号内的标识符）
#    · 实际：测试源码里真实存在的 `void 方法名(` 声明 / Playwright 的 test('名字')
#
#  ⚠️ 编码要求：本文件必须保存为 **UTF-8 with BOM**。
#     本机只有 Windows PowerShell 5.1，系统代码页 936(GBK)：无 BOM 时脚本会按
#     ANSI 解读 → 中文乱码 + 解析失败（见 docs/agents/README.md §7.1）。
#     注意：用编辑工具改完本文件后 BOM 会丢，必须补回。
#
#  用法：
#    powershell -File scripts/check_test_coverage_gaps.ps1
#    powershell -File scripts/check_test_coverage_gaps.ps1 -Detailed      # 逐条列出缺口
#    powershell -File scripts/check_test_coverage_gaps.ps1 -FailOnGap    # 有缺口则退出码 1（供 CI 卡口用）
#
#  退出码：0 = 检查完成（**默认不因"有缺口"而失败**，因为 W1 阶段缺口必然大量存在）
#          1 = 检查本身失败（缺文件、解析不到任何期望项）或指定了 -FailOnGap 且有缺口
# =============================================================

[CmdletBinding()]
param(
    # 仓库根目录；默认取本脚本所在目录的上一级
    [string]   $RepoRoot,
    # 映射表路径（相对仓库根）
    [string]   $MappingDoc = 'docs\testing\验收项-测试映射.md',
    # 实际测试代码的扫描根（相对仓库根）；目录不存在则跳过并提示
    [string[]] $TestRoots  = @('server\src\test', 'admin\src\test', 'web\tests', 'tests\golden-path'),
    # 有缺口时是否以退出码 1 结束（CI 卡口用）
    [switch]   $FailOnGap,
    # 逐条打印缺口清单（默认只打印分组统计 + 前后各若干条）
    [switch]   $Detailed
)

$ErrorActionPreference = 'Continue'

# 让中文在 UTF-8 捕获端（CI 日志 / 本 harness）也能正确读出
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

# -------------------------------------------------------------
# 0. 非测试方法名的反引号词（白名单式排除，**必须显式登记**）
#    这些词在映射表里也被反引号包着，但不是测试方法名。
#    刻意不做"前缀猜测"式过滤：宁可多报（人一眼能看出是误报），
#    也不要因为猜错前缀而**漏掉**一条真实验收项（漏测才是本脚本存在的理由）。
# -------------------------------------------------------------
$NotTestNameTokens = @(
    'APP_PROFILE',  # ADR-0010：环境档位变量名
    'Authorization',# HTTP 头
    'E2E',          # 「层次」列的取值
    'INSERT'        # SQL 关键字（M4_comment_no_third_level 的断言要点里出现）
)

# 测试方法名形如 M4_like_idempotent_concurrent / ARCH_no_cross_module_dependency
$NamePattern  = '^[A-Z][A-Za-z0-9_]+$'
# 反引号内的词
$BacktickPattern = '`([A-Za-z][A-Za-z0-9_]+)`'

# 实际代码里的方法名：
#   · Java/Kotlin：**带测试注解的** `void M4_xxx(`（覆盖 JUnit5 / jqwik / REST Assured 测试）
#   · Playwright：test('M2_e2e_register_login', ...)
#
# 为什么 Java 侧要求「同一成员声明块里有测试注解」：
# 只按 `void xxx(` 抓会把 `private void assertRejected(...)`、`@BeforeEach` 之类的
# 辅助方法也算成测试，于是「多余：映射表未登记」被噪声淹没，人就不看了。
# 判定方式：从 `void NAME(` 往前取一段文本，截到最近的 `}` 或 `;`（= 上一个成员结束处），
# 该块内必须出现下列注解之一，才算一个测试方法。
$TestMethodAnnotations = '@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Property)\b'
$JavaMethodPattern     = '\bvoid\s+([A-Za-z_][A-Za-z0-9_]*)\s*\('
$JsTestPattern         = '(?:test|it)\s*\(\s*[''"`]([A-Za-z_][A-Za-z0-9_]*)[''"`]'
# 向前回溯窗口：注解+多行 @DisplayName 也够用，不用取整文件
$LookBehindChars       = 800

# 为什么先剥注释再扫：Javadoc 里的用法示例常写成
#     <pre>{@code @Test void M4_xxx() { ... } }</pre>
# 不剥注释就会把示例里的 M4_xxx 当成真实测试方法，污染「多余」清单。
# 局限：`//` 若出现在字符串字面量里（例如 http://）会被误截断；本脚本只用文本判断
# 方法声明与注解，截断 URL 字符串不影响判定结果。
function Remove-SourceComments {
    param([string]$Text)
    $t = [regex]::Replace($Text, '/\*.*?\*/', ' ', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    return [regex]::Replace($t, '(?m)//[^\r\n]*', ' ')
}

# -------------------------------------------------------------
# 1. 定位仓库根
# -------------------------------------------------------------
if (-not $RepoRoot) { $RepoRoot = Split-Path $PSScriptRoot -Parent }
if (-not (Test-Path $RepoRoot)) { throw "仓库根不存在：$RepoRoot" }
$RepoRoot = (Resolve-Path $RepoRoot).Path
$mapPath  = Join-Path $RepoRoot $MappingDoc

if (-not (Test-Path $mapPath)) { throw "找不到映射表：$mapPath（验收项的期望清单只能来自它）" }

Write-Host ""
Write-Host "=== 验收项覆盖缺口检查 ===" -ForegroundColor Cyan
Write-Host ("  仓库根   : {0}" -f $RepoRoot)
Write-Host ("  期望来源 : {0}" -f $MappingDoc)

# -------------------------------------------------------------
# 2. 解析映射表：只吃表格行（避免正文/代码块里的反引号造成假期望）
#    · 每遇到 "## 标题" 就切换所属分段（M0..M8 / 跨里程碑）
#    · 一行的「测试方法名」列若带 [阻塞 P1-x] 标记，则记为阻塞项
# -------------------------------------------------------------
$expected   = New-Object System.Collections.ArrayList   # @{Name;Section;Blocked;Item}
$seen       = New-Object 'System.Collections.Generic.HashSet[string]'
$excluded   = New-Object 'System.Collections.Generic.HashSet[string]'   # 被排除的词（把过滤过程显式化，便于人工审计）
$section    = '(未分节)'
$lineNo     = 0
$blockedCnt = 0

foreach ($line in [System.IO.File]::ReadAllLines($mapPath, [System.Text.Encoding]::UTF8)) {
    $lineNo++

    if ($line -match '^##\s+(.+?)\s*$') {
        $section = $Matches[1].Trim()
        continue
    }
    if (-not $line.TrimStart().StartsWith('|')) { continue }   # 非表格行 → 跳过

    $isBlocked = $line -match '\[阻塞'
    foreach ($m in [regex]::Matches($line, $BacktickPattern)) {
        $tok = $m.Groups[1].Value
        if ($tok -cnotmatch $NamePattern)       { [void]$excluded.Add("$tok（首字母非大写）"); continue }   # 必须用 -cnotmatch：PowerShell 的 -notmatch **不区分大小写**，会放进 uk_/password 这类假期望
        # 命名约定守卫：本项目测试方法名统一为 <AREA>_<snake_case>（如 M4_like_idempotent_concurrent）。
        # 表格的「断言要点」列天然会用反引号引用异常类名/常量（如 DuplicateKeyException、String），
        # 若不按此约定过滤，它们会被当成"期望的测试方法"，从而出现永远补不上的幽灵缺口。
        if ($tok -cnotmatch '_')                { [void]$excluded.Add("$tok（无下划线，不符合 <AREA>_<snake_case> 约定）"); continue }
        if ($NotTestNameTokens -contains $tok)  { [void]$excluded.Add("$tok（显式白名单）");         continue }
        if ($seen.Add($tok)) {
            [void]$expected.Add([pscustomobject]@{
                Name    = $tok
                Section = $section
                Blocked = $isBlocked
                Line    = $lineNo
            })
            if ($isBlocked) { $blockedCnt++ }
        }
    }
}

if ($expected.Count -eq 0) { throw "映射表里没解析出任何期望测试方法名 —— 解析规则或文档格式已漂移，先修正本脚本再谈覆盖" }

# -------------------------------------------------------------
# 3. 扫描实际测试代码
# -------------------------------------------------------------
$actual   = New-Object 'System.Collections.Generic.HashSet[string]'
$scanned  = New-Object System.Collections.ArrayList
$skipped  = New-Object System.Collections.ArrayList

foreach ($rel in $TestRoots) {
    $root = Join-Path $RepoRoot $rel
    if (-not (Test-Path $root)) { [void]$skipped.Add($rel); continue }

    $files = Get-ChildItem -Path $root -Recurse -File -Include '*.java', '*.kt', '*.ts', '*.js' -ErrorAction SilentlyContinue |
             Where-Object { $_.FullName -notmatch '\\(node_modules|target|build|dist)\\' }
    $n = 0
    foreach ($f in $files) {
        $n++
        $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        $code = Remove-SourceComments $text

        # (a) Java/Kotlin：必须是「同一成员声明块里有测试注解」的 void 方法
        foreach ($m in [regex]::Matches($code, $JavaMethodPattern)) {
            $start = [Math]::Max(0, $m.Index - $LookBehindChars)
            $before = $code.Substring($start, $m.Index - $start)
            # 截到上一个成员的结尾（`}` 或 `;`），避免把上一个测试方法的注解算到本方法头上
            $cut = [Math]::Max($before.LastIndexOf('}'), $before.LastIndexOf(';'))
            if ($cut -ge 0) { $before = $before.Substring($cut + 1) }
            if ($before -match $TestMethodAnnotations) { [void]$actual.Add($m.Groups[1].Value) }
        }

        # (b) Playwright/Jest：test('名字', ...)
        foreach ($m in [regex]::Matches($code, $JsTestPattern)) { [void]$actual.Add($m.Groups[1].Value) }
    }
    [void]$scanned.Add(("{0}（{1} 个文件）" -f $rel, $n))
}

$sourcesInfo = ($scanned -join '、')
if ($skipped.Count -gt 0) {
    $skipNote = "跳过（目录不存在）：" + ($skipped -join '、')
    $sourcesInfo = if ($sourcesInfo) { $sourcesInfo + "；" + $skipNote } else { $skipNote }
}
Write-Host ("  实际来源 : {0}" -f $sourcesInfo)
Write-Host ""

# -------------------------------------------------------------
# 4. 差集
# -------------------------------------------------------------
$expectedNames = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($e in $expected) { [void]$expectedNames.Add($e.Name) }

# 缺口 = 期望里有、实际里没有，且**未被 P1 阻塞**（阻塞项现在写必然返工，不算欠账）
$gaps      = @($expected | Where-Object { -not $actual.Contains($_.Name) -and -not $_.Blocked })
$gapsBlock = @($expected | Where-Object { -not $actual.Contains($_.Name) -and  $_.Blocked })
$done      = @($expected | Where-Object {  $actual.Contains($_.Name) })
$extra     = @($actual   | Where-Object { -not $expectedNames.Contains($_) } | Sort-Object)

# 分段统计（把「已实现/合计」按映射表的分段列出，便于定位是哪一段在欠账）
$bySection = $expected | Group-Object Section | Sort-Object Name

Write-Host "--- 总览 ---" -ForegroundColor Yellow
Write-Host ("  期望测试方法名 : {0} 条（其中 [阻塞] {1} 条，按测试策略**不应实现**）" -f $expected.Count, $blockedCnt)
Write-Host ("  已实现         : {0} 条" -f $done.Count)
Write-Host ("  **缺口**       : {0} 条" -f $gaps.Count) -ForegroundColor Red
if ($excluded.Count -gt 0) {
    Write-Host ("  已排除的非测试词 : {0} 个 —— {1}" -f $excluded.Count, (($excluded | Sort-Object) -join '、')) -ForegroundColor DarkGray
}
Write-Host ""

Write-Host "--- 分段统计（已实现 / 期望，不含阻塞项计入缺口）---" -ForegroundColor Yellow
foreach ($g in $bySection) {
    $secDone = @($g.Group | Where-Object { $actual.Contains($_.Name) }).Count
    $secGap  = @($g.Group | Where-Object { -not $actual.Contains($_.Name) -and -not $_.Blocked }).Count
    $secBlk  = @($g.Group | Where-Object { $_.Blocked }).Count
    $color   = if ($secGap -gt 0) { 'Gray' } else { 'Green' }
    $tail    = if ($secBlk -gt 0) { ("  [阻塞 {0}]" -f $secBlk) } else { '' }
    # 注意：Group-Object 的结果对象把分组键放在 .Name 上，没有 .Section 属性
    Write-Host ("  {0,-46} {1,3} / {2,-3}  缺口 {3,-3}{4}" -f $g.Name, $secDone, $g.Group.Count, $secGap, $tail) -ForegroundColor $color
}
Write-Host ""

# -------------------------------------------------------------
# 5. 明细
# -------------------------------------------------------------
$showAll = $Detailed.IsPresent
function Show-List {
    param([string]$Title, $Items, [string]$Color, [int]$Limit)
    if (-not $Items -or @($Items).Count -eq 0) { return }
    Write-Host ("--- {0}（{1} 条）---" -f $Title, @($Items).Count) -ForegroundColor $Color
    $i = 0
    foreach ($it in $Items) {
        $i++
        if (-not $showAll -and $i -gt $Limit) {
            Write-Host ("  ... 其余 {0} 条见 -Detailed 输出" -f (@($Items).Count - $Limit)) -ForegroundColor DarkGray
            break
        }
        if ($it -is [pscustomobject] -and $it.PSObject.Properties.Name -contains 'Section') {
            Write-Host ("  {0,-56} [{1}]" -f $it.Name, $it.Section) -ForegroundColor $Color
        } else {
            Write-Host ("  {0}" -f $it) -ForegroundColor $Color
        }
    }
    Write-Host ""
}

Show-List -Title '缺口：映射表有、代码里没有（**这就是要补的测试**）' -Items $gaps      -Color 'Red'      -Limit 40
Show-List -Title '阻塞项：依赖未决 P1，现在写会返工（不计入缺口）'      -Items $gapsBlock -Color 'DarkYellow' -Limit 20
Show-List -Title '多余：代码里有、映射表未登记（确认是否需要补进映射表）' -Items $extra     -Color 'Magenta'  -Limit 20

# -------------------------------------------------------------
# 6. 结论与退出码
# -------------------------------------------------------------
if ($gaps.Count -gt 0) {
    Write-Host ("结论：仍有 {0} 条验收项没有对应测试（W1 阶段属预期，随 M2–M6 收敛）。" -f $gaps.Count) -ForegroundColor Yellow
} else {
    Write-Host "结论：映射表中所有非阻塞验收项都已有对应测试方法。[PASS]" -ForegroundColor Green
}

if ($FailOnGap.IsPresent -and $gaps.Count -gt 0) {
    Write-Host "因为指定了 -FailOnGap，退出码 = 1" -ForegroundColor Red
    exit 1
}
exit 0
