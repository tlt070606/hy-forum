# CLAUDE.md

> **想直接上手？先看 [`docs/交接说明.md`](docs/交接说明.md)** —— 它写了怎么把项目跑起来、接口文档在哪、有哪些坑。
>
> **本文件不承载事实。**
> 项目的准入说明是 [`AGENTS.md`](AGENTS.md)，**唯一事实来源是 [`docs/`](docs/README.md)**。
> 这里只写两件事：**去哪读**、**跑什么命令**。
> 任何"项目现在是什么样"的描述都不该写在这里 —— 只要写一次副本，它就会与 `docs/` 漂移，
> 而漂移的副本比没有副本更危险（它会让人基于过期事实做决定）。这一条本项目已经吃过一次亏。

---

## 开工前必读（按顺序，不要跳）

| # | 读什么 | 为什么 |
|---|---|---|
| 1 | [`AGENTS.md`](AGENTS.md) | 项目定位与**九条铁律**。冲突时以它和 `docs/PLAN.md` 为准 |
| 2 | [`docs/PLAN.md`](docs/PLAN.md) | 范围、里程碑、**验收标准**、风险、待确认事项 |
| 3 | [`docs/agents/工作计划.md`](docs/agents/工作计划.md) | 协作看板：**§4 广播区（每次开工必读）**、§2 文件所有权表、§3 变更请求队列、**§6.5 交接事项** |
| 4 | [`docs/agents/README.md`](docs/agents/README.md) §7 | **工程约定与本机环境陷阱** —— 不读这一节，你的第一条命令就可能失败 |
| 5 | [`docs/testing/README.md`](docs/testing/README.md) | 测试策略（三条纪律、`*IT.java` 陷阱、为什么不用 Testcontainers） |

按任务类型另需：改表读 [`docs/db/README.md`](docs/db/README.md)（冻结流程）、改接口读 [`docs/技术方案.md`](docs/技术方案.md) §6、部署读 [`docs/ops/deployment.md`](docs/ops/deployment.md)。
全量索引：[`docs/README.md`](docs/README.md)。

---

## 常用命令

本机为 **Windows PowerShell 5.1**（无 `pwsh` 7）。所有 `.ps1` 用 `powershell -File <脚本>` 调用。

```powershell
# ⚠️ 每个新会话的第一件事：显式指定 JDK。
# 宿主进程可能仍持旧环境变量，子进程会继承它 —— 不设这条，Maven 会用错 JDK 编译。
$env:JAVA_HOME='D:\develop\jdk21'

# ---- 后端（工作目录 server/）----
mvn clean package                  # 产出 target/hy-forum-server-0.0.1-SNAPSHOT.jar
mvn test                           # 全部测试（含 ArchUnit 架构约束）
mvn test -Dtest=M1CaptchaTest                     # 单个类
mvn test -Dtest='M1*Test'                         # 通配一组
mvn test -Dtest=M1CaptchaTest#captcha_ttl_is_300s # 单个方法

# ---- 测试前应重建测试库（隔离补偿，见 docs/testing/README.md §3.1）----
powershell -File scripts/init_test_db.ps1 -User root -Password '<pwd>'

# ---- 数据库验收（-Init = 建库+建表+灌 seed+校验；不带 = 只校验）----
powershell -File scripts/verify_m0.ps1 -User root -Password '<pwd>' -Init

# ---- 导出接口契约（启动 jar → 抓 /v3/api-docs → 停进程）----
powershell -File scripts/export_openapi.ps1        # 加 -SkipBuild 复用已有 jar

# ---- 跑应用（参数必须整体加引号，否则会被 PowerShell 拆成主类名；不加则中文日志乱码）----
java '-Dstdout.encoding=UTF-8' -jar server/target/hy-forum-server-0.0.1-SNAPSHOT.jar

# ---- 覆盖率缺口（哪条验收项还没有测试）----
powershell -File scripts/check_test_coverage_gaps.ps1 -Detailed
```

其他脚本：`scripts/toolchain-check/`（JDK 环境预检，反向验证运行时必须是 21）、`scripts/GenBcryptHash.java`（生成 seed 用的 BCrypt 哈希）。

---

## 三条会让你白忙一场的事

细节全在上面的文档里，这里只点名 —— 它们的共同点是：**不遵守就一定会失败，而且失败原因看起来与你的改动无关**。

1. **没设 `JAVA_HOME`** → Maven 报 `invalid target release`（看起来像代码问题，其实是环境）。
2. **改完 `.ps1` 没补 BOM** → PowerShell 5.1 按 GBK 解读，中文乱码且**解析失败**（看起来像脚本写错了，其实是编码）。**文件写入工具会丢 BOM。**
3. **手工改了契约**（`docs/db/schema.sql` 或 `openapi.json`）→ 其他 agent 会基于旧契约继续开发，漂移无法察觉。**发现契约不足时停下来提 CR**，见看板 §3。

---

## 给 Claude Code 的约定

- **本文件不写事实。** 需要新增项目事实时写进 `docs/` 的对应文档（`docs/` 是唯一事实来源），不要写在这里。
- 发现新的**环境陷阱**，补进 [`docs/agents/README.md`](docs/agents/README.md) **§7.2**。
- 发现新的**协作问题**，补进 [`docs/agents/工作计划.md`](docs/agents/工作计划.md) §4 广播区或 §6.5 交接事项。
- 提交信息用 Conventional Commits；`main` 保持可发布，功能走 `feature/<module>-<short-desc>`。
- **没有命令输出的"完成"不算完成。** 测试要求「红 → 绿」两次输出，见 `docs/testing/README.md` §2。
