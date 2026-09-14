# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

Hy论坛 —— 中文综合论坛，含「资源分享」版块（发帖可附网盘链接与提取码）。

后端 Java 21 + Spring Boot 3 **模块化单体**（单 Maven 模块 + 分层 package）；前端 **uni-app 唯一前端编译三端**（H5 + 安卓 App + 微信小程序）。小程序端有两条硬约束使其**在可预见将来无法上线**（个人主体不支持 `web-view`、`request` 只允许已备案域名），需求方已知并接受 —— **不要当"将来能上线的产品"投入精力**。

**当前状态**：M0（数据库地基）与 M1（后端骨架 + 认证）已完成并通过验收，`openapi.json` 已产出（8 路径）。前端工程（`web/**`）**尚未创建**，M2 任务书待按 uni-app 重写。目标定位为**本地可用版**，M8（上线）暂不启动。

## 铁律

**开工前必读 [`AGENTS.md`](AGENTS.md)** —— 它是准入说明，与本文件冲突时以它和 `docs/PLAN.md` 为准。**九条**铁律中最容易踩的四条：

1. **文档先行**：先改文档再改代码。`docs/` 是唯一事实来源。
2. **禁止跨模块调用**：七个业务包之间只允许依赖 `com.hyforum.common` 与 `com.hyforum.domain`，禁止调用其他模块的 Mapper 或 Service 实现（`ArchitectureRulesTest` 守门）。
3. **禁止硬编码密钥**：AccessKey、口令、域名一律走环境变量。**前端绝不持有 AccessKey**，上传走 OSS 服务端签名直传（铁律 8）。
4. **禁止在服务器上构建**：`mvn package` / `npm run build` 内存峰值超 1G，2 GiB 服务器会 OOM（在 CI 或本地构建）。

## 常用命令

本机为 Windows PowerShell 5.1（**无 `pwsh` 7 可用**）。所有 `.ps1` 一律用 `powershell -File <脚本>` 调用。

```powershell
# 本会话必须显式指定 JDK —— 宿主进程仍持旧环境变量（jdk17），子进程会继承它
$env:JAVA_HOME='D:\develop\jdk21'

# 构建（工作目录 server/）
mvn clean package                              # 产出 target/hy-forum-server-0.0.1-SNAPSHOT.jar
mvn test                                       # 全部测试（含 ArchUnit 架构约束）
mvn test -Dtest=M1CaptchaTest                  # 单个测试类
mvn test -Dtest='M1*Test'                      # 按通配符跑一组
mvn test -Dtest=M1CaptchaTest#captcha_ttl_is_300s   # 单个测试方法

# 跑测试前必须重建测试库（放弃 Testcontainers 后的隔离补偿，见下）
powershell -File scripts/init_test_db.ps1 -User root -Password '<pwd>'

# M0 数据库验收（-Init = 建库 + 建表 + 灌 seed + 校验；不带 = 只校验）
powershell -File scripts/verify_m0.ps1 -User root -Password '<pwd>' -Init

# 导出接口契约（启动 jar → 抓 /v3/api-docs → 停进程）
powershell -File scripts/export_openapi.ps1                # -SkipBuild 复用已有 jar
```

起应用用 `java '-Dstdout.encoding=UTF-8' -jar server/target/hy-forum-server-0.0.1-SNAPSHOT.jar`。参数**必须整体加引号**，否则 PowerShell 会把它拆成主类名；不加这个参数中文日志会乱码（JVM 的 `stdout.encoding` 跟随系统代码页 GBK）。

## 架构

### 契约是单写者资源

两份文件是全项目的**契约**，改动必须走流程，**任何人不得手工编辑**：

| 契约 | 位置 | 规则 |
|---|---|---|
| 表结构 | `docs/db/schema.sql`（16 张表 + 1 个 CHECK 约束） | 冻结流程见 `docs/db/README.md`：**先改 schema.sql，再改库，再写 `docs/db/migrations/` 增量脚本**。禁止跨模块建表改表 |
| 接口 | `openapi.json`（仓库根） | **由后端注解导出**（`scripts/export_openapi.ps1`），L1 审核后冻结。禁止手工编辑 |

契约一旦在某个 agent 手里"顺手改一下"，其他 agent 就会基于旧契约继续开发，**漂移无法察觉**。发现契约不足时：**不要改契约、不要绕过**，停下来提变更请求（CR），见 `docs/agents/工作计划.md` §3。

### 后端包结构（`server/src/main/java/com/hyforum/`）

```
common/      基础设施：ApiResponse/ErrorCode/PageResult、全局异常、Redis、Sa-Token、限流、拦截器
domain/      跨模块共享层：实体 + Mapper（任何模块可读，改动需 L1 裁决，因为影响所有模块）
auth/ post/ media/ interaction/ audit/ notify/ admin/   七个业务包，互不依赖
```

七个业务包只允许依赖 `common` 与 `domain`。每个空包里有 `PackageMarker`，作用是让空包真实存在于编译产物中 —— 否则 ArchUnit 规则会**空转通过**（防假绿）。

**Sa-Token 前后台隔离**靠两套独立 `StpLogic`（`StpUserUtil` / `StpAdminUtil`），不靠 token 前缀。

### 前端（尚未创建）

uni-app + Vue3 + Vite + TS + wot-design-uni。选型在 2026-09-14 发生反转（原为 Vue 3 SPA + Vant），原因与硬约束见 `docs/adr/0011`。**后端零改动**是这次反转的前提。

## 测试

**测试策略的唯一依据是 [`docs/testing/README.md`](docs/testing/README.md)**，验收项与测试方法名的对照表是 [`docs/testing/验收项-测试映射.md`](docs/testing/验收项-测试映射.md)。核心约定：

- **测试从「验收标准」反推，不从「实现」反推。** 每条验收项（`PLAN.md` §4 的 M0–M8）都必须有对应测试方法名。
- **测试方法名必须带验收项编号**：`M4_like_idempotent_concurrent`、`SEC_cannot_edit_others_post`。脚本据此自动查出漏测。
- **测试类一律以 `Test` 结尾**。Maven Surefire 默认**不跑 `*IT.java`**（那是 Failsafe 的约定，本项目没配）—— 命名成 `XxxIT.java` 会**静默跳过**，测试全绿但从没执行过。
- **测试不连开发库**：连本机 MySQL 的独立测试库 `hy_forum_test`（由 `scripts/init_test_db.ps1` 每次重建，**DDL 从 `schema.sql` 派生，不维护第二份建表脚本**）+ VM 内 Redis `192.168.100.128:6380`。**本项目不用 Testcontainers**（本机不装 Docker Desktop），理由与三条补偿措施见 `docs/testing/README.md` §3.1。

### 两个已实测的坑

1. **`@Valid` 参数校验发生在 Controller 方法体之前。** 想测限流返回 `429` 时，如果请求体不合法，会**先**返回 `400`，永远走不到限流逻辑 —— "限流没生效"这个 bug 会被测试掩盖。**测限流/越权/业务分支时，一律用契约允许的合法请求体**，只改要触发的那一个条件。
2. **一个从未失败过的测试，不能证明它在测任何东西。** 交付要求「红 → 绿」两次命令输出。变异测试（故意改坏实现看测试是否变红）是唯一能证明测试有效的手段。

## 本机环境陷阱（会直接导致命令失败）

**修改 `.ps1` 文件后必须补回 UTF-8 BOM。** 本机 PowerShell 5.1 + GBK 代码页（936）下，无 BOM 的 `.ps1` 会被按 GBK 解读 → 中文乱码**且解析失败**（实测 19 处语法错误，脚本根本跑不起来）。**文件写入工具会丢掉 BOM**，改完必须补：

```powershell
$p = '脚本路径.ps1'
$t = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($true)))
```

反向约定：**`.sql` 与 `.md` 不要带 BOM**（`mysql` 客户端不期望 BOM）。且**不要用 `Get-Content ... | mysql` 喂 SQL 文件**（PowerShell 管道会改变文本编码，中文种子数据会写坏），必须用字节级重定向 `mysql ... < file`。

其余环境事实（已装 JDK 21 的位置、MySQL 8.0.34、VM Redis、VM 磁盘仅剩 ~3 GB、阿里云服务器状态、文件系统与网络权限**策略会变**）统一登记在 [`docs/agents/README.md`](docs/agents/README.md) §7.2 —— **每次发现新的环境陷阱都要补进那一节**，否则下一个 agent 会再踩一次。

⚠️ **`redis`（VM 内 6379）必须保持运行** —— 本机另一个项目 `D:\test\hm-dianping` 依赖它，停掉后故障是**静默**的（不报错，只是卡在 `SYN_SENT` 重连，功能全废）。腾 VM 空间时只停 `rabbitmq / seata-server / nacos / nginx / mysql`。**停任何别人可能在用的服务前，先查清谁在依赖它。**

## 多 Agent 协作

本项目有一套已落地的 L0–L3 分层协作规范（[`docs/agents/README.md`](docs/agents/README.md)），**动手前先读协作看板 [`docs/agents/工作计划.md`](docs/agents/工作计划.md) §4 广播区与 §2 文件所有权表**：

- **同一个路径同一时刻只能有一个所有者。** 所有权在 `工作计划.md` §5 登记，任务结束即交还。
- **L1（主 Agent）是契约守护者**：独占 `schema.sql`、`openapi.json`、CI 配置、`docs/**`、`openapi.json` 生成的前端类型；**L1 禁止写业务模块实现**（写业务代码就没人守契约了）。
- **L2（模块实现者）只能改任务书指定的路径**，禁止改契约、改其他模块的文件、与其他 L2 私下达成本文档未记录的约定。
- **`git status` / `git diff` 是校验 agent 是否越界写文件的权威手段。**
- L2 一律用**不继承对话上下文**的子 agent：它只能读 `docs/`，因此**文档写不清的地方，就是 agent 会自由发挥的地方** —— 派活时第一反应该是「回文档里写清楚」，而不是「写更长的提示词」。

## 提交规范

Conventional Commits：`type(scope): 描述`，type 取 `feat` `fix` `docs` `refactor` `test` `chore`，scope 用模块名。示例：`feat(post): 支持资源版块网盘链接归一化`。`main` 保持可发布，功能开发走 `feature/<module>-<short-desc>`。

## 文档地图

| 想了解 | 去哪里 |
|---|---|
| 协作铁律与准入 | [`AGENTS.md`](AGENTS.md) |
| 范围、里程碑、验收标准、风险 | [`docs/PLAN.md`](docs/PLAN.md) |
| 完整技术设计 | [`docs/技术方案.md`](docs/技术方案.md) |
| 部署（单一配置、无档位） | [`docs/ops/deployment.md`](docs/ops/deployment.md) |
| 建表脚本 | [`docs/db/schema.sql`](docs/db/schema.sql) |
| 关键决策与理由 | [`docs/adr/`](docs/adr/) |
| 方案评审结论与遗留项 | [`docs/review/`](docs/review/) |
| AI 协作规范与协作看板 | [`docs/agents/`](docs/agents/) |
| 测试策略与验收项对照表 | [`docs/testing/`](docs/testing/) |
| 全部文档索引 | [`docs/README.md`](docs/README.md) |
