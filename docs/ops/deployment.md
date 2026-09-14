# 部署方案（单一配置）

> 本文件是部署的**唯一依据**。
> 核心原则：**本地开发为基准、上线前先升配；只维护一份构建产物与一份配置，不存在部署档位。**
> 版本 **v2.0**（2026-09-14）。**v2.0 是一次结构性变更**：取消部署双档位（`lowmem` / `standard`），改为单一配置；性能基准由「2 vCPU / 2 GiB」改为「本地开发」；原 2C2G 的参数核算**未丢弃**，降为[附录 A](#附录-a若最终目标仍为-2-vcpu--2-gib)备用。
> 决策依据：`docs/review/2026-09-14-方案评审记录.md` §10 **D4**（2026-09-14）与 [`../adr/0010-部署以本地开发为基准并取消档位.md`](../adr/0010-部署以本地开发为基准并取消档位.md)。**本文档取代 [`../adr/0004-部署双档位.md`](../adr/0004-部署双档位.md)。**

---

## 0. 设计原则

1. **单一产物、单一配置**：一份 JAR、一份前端 `dist/`、一份 `docker-compose.yml`、一份 `.env`。不存在 `docker-compose.lowmem.yml` 之类的档位文件，也不存在 `APP_PROFILE` 开关。
2. **本地优先**：开发期以本地运行为准，**不预先用低配内存约束限制设计与实现**。理由：先证明「功能与性能可以做到」，再在真实规格上做取舍；反过来（先按最低配写死）会把大量精力花在微调上，且容易掩盖真正的性能问题。
3. **上线前一次性定规格**：上线前必须先回填目标服务器规格，并按 §6 复算参数、回填 §2。**不允许"拿一份来路不明的参数直接上生产"**。
4. **不预先自我设限，但不放弃自觉**：不限内存 ≠ 可以写低效代码。§5 的性能实践与规格无关，一律遵守。

> **与铁律的关系**：`AGENTS.md` 铁律 2（零代码分叉）在单一配置下**自动满足**（只有一份产物、一份配置）；铁律 6（禁止在服务器上构建）**继续有效**，其理由是构建期内存峰值（`mvn package` / `npm run build` 均超 1G）会打挂低配服务器，与档位机制无关。本次 D4 决策**不修改 `AGENTS.md`**。

---

## 1. 目标环境

| 阶段 | 环境 | 内存约束 | 状态 |
|---|---|---|---|
| **开发期（当前）** | 本地开发机 | **不设约束** | 立即开始 |
| **测试 / 演示** | 本地或局域网 | 不设约束 | 可选 |
| **上线** | **升配后的阿里云服务器** | 规格待定 → **上线前回填并复算** | 待定，见 §4 |

现有服务器为阿里云 **2 vCPU / 2 GiB**、Ubuntu 22.04、ESSD 40 GiB、公网 IP 8.138.237.212、到期 **2027-01-06**。按 D4 决策，**它不再是本期部署目标**（其参数核算保留在附录 A）；是否续费、是否变配，需在到期前决定。

---

### 1.1 准生产演练环境（本地 VMware 虚拟机）

| 项 | 值 |
|---|---|
| 位置 | 本机 VMware Workstation 16.1（安装在 `D:\develop\VMware`），VM 配置 `D:\VMImages\Ubuntu24.04\Ubuntu24.04.vmx` |
| 访问方式 | `ssh roott@192.168.100.128`（VMnet8 NAT 网段 `192.168.100.0/24`）；专用密钥 `~/.ssh/ubuntu_vm`，`~/.ssh/config` 已配好该 Host |
| 系统 | Ubuntu 24.04.4 LTS（内核 6.8） —— 与最终服务器同属 Ubuntu 系 |
| 规格 | **2 vCPU / 1967 MB / 19 GB 磁盘** |
| 已装 | Docker **29.1.3**、OpenJDK **21.0.12**（`/usr/lib/jvm/java-21-openjdk-amd64`，已设为 `/usr/bin/java` 的 alternatives） |
| 用途 | ① **本项目开发期的 Redis 宿主机**（见 §3）② **部署演练 + 附录 A 低配参数验证场** —— 它的规格与阿里云那台（`PLAN.md` A1/A9：2 vCPU / 2 GiB）几乎相同，是验证「2 GiB 到底能不能跑」的现成场地 |
| 本项目已启用 | 仅一个容器：`hy-redis`（`redis:7-alpine`，`-p 6380:6379`，`--restart unless-stopped`，`-v hy-redis-data:/data`，参数 `--maxmemory 256mb --maxmemory-policy allkeys-lru --save 900 1`，与 §2 一致） |

**2026-09-14 处置记录（可回滚）**：

VM 内原有**另一个项目**（Spring Cloud Alibaba 微服务栈）的六个容器常驻运行，占用约 **1.2 GB** 内存，并把 **3306 / 6379 / 5672 / 8848 / 7099 / 18080** 全部占满。需求方确认后已执行：

```bash
# 已执行（仅 stop，未删除容器与镜像，基本可回滚）
docker stop rabbitmq seata-server nacos nginx mysql redis

# 恢复原状（需要时执行，顺序建议先基础设施后应用）
docker start mysql redis nacos seata-server rabbitmq nginx
```

停止后内存占用从 1596 MB 降至 **360 MB**，可用内存 **1606 MB**。

> ⚠️ **必须注意：`redis`（6379）已经启回，并且要保持运行。**
> 原因：**本机还有另一个项目依赖它** —— `D:\test\hm-dianping` 的 `application.yaml` 里写的是
> `spring.redis.host=192.168.100.128 / port=6379`。D6 把六个容器一起停掉时**连带把这个项目弄断了**
> （现象：该进程一直停在 `SYN_SENT` 重连，不报错、只是所有 Redis 相关功能失效），
> 事后才发现并恢复。当前状态：`Established` 已恢复。
>
> **教训**：停别人在用的服务前，要先查清**谁在依赖它**（`Get-NetTCPConnection` 看谁连着这个端口、
> 或搜一下本机其它工程的配置文件），不能只看"我这个项目不需要它"。这与本项目
> [`../agents/README.md`](../agents/README.md) §3 交接协议的精神一致：**影响范围要显式确认，不能默认**。
>
> 因此正确的"腾空间"命令是**只停本项目确认不需要的**：
> ```bash
> docker stop rabbitmq seata-server nacos nginx mysql   # 保留 redis 给 hm-dianping
> ```

> **顺带得到的证据**：那套栈里的 `hmall` 与 `sentinel-dashboard` 两个容器此前均为 **`Exited (137)`= OOMKilled** —— 即 **2 GiB 跑微服务全家桶（nacos + seata + rabbitmq + sentinel）是不够的**。这从反面支持了本项目的架构选择：**单体 + MySQL + Redis**，而不是拆微服务。

**仍然存在的约束（务必知晓）**：

1. **磁盘仅剩约 3.0 GB（已用 83%）** → 不要在此 VM 内拉取大镜像；需要演练完整栈时先扩磁盘到 ≥ 40 GB。
2. **VM 内存 1967 MB**，与阿里云那台基本一致 → 它验证附录 A 参数**正合适**，但**不适合在其内部做 Maven 构建**（`mvn package` 内存峰值 > 1G，与 `AGENTS.md` 铁律 6 同因：构建一律在本地或 CI 完成）。
3. VM 为 **VMnet8 NAT** 网络 → 局域网外无法访问，**不能**当作"给朋友用"的服务器。

> **凭据不入库**：VM 与阿里云服务器的口令一律不写入本仓库，只记录访问方式；凭据由本地 `~/.ssh/` 与配置管理负责（对应 `AGENTS.md` 铁律 5 的精神）。
>
> **另**：`~/.ssh/config` 中另有 `myserver → 8.138.237.212`（即 `PLAN.md` A1 那台阿里云 ECS，2 vCPU / 2 GiB），可直连。按 **D4 决策**（[`../adr/0010`](../adr/0010-部署以本地开发为基准并取消档位.md)），**上线推迟到升配之后**，当前不部署到该机器。

## 2. 统一参数（开发与上线共用一份）

> 下表是**唯一的一份参数**。本地开发与上线使用同一份配置，仅通过 `.env` 注入不同环境的值（数据库地址、OSS Bucket、域名等**环境相关**项），而**容量类参数不再分档**。

| 组件 | 参数 | 说明 |
|---|---|---|
| MySQL | `innodb_buffer_pool_size=512M` | 8–16G 开发机与 4C8G 服务器均可承受；1000–10000 条帖子的数据集完全装得下 |
| MySQL | `max_connections=100` | 与 Hikari `maximum-pool-size=20` 匹配（单后端实例 + 定时任务，100 留足余量） |
| MySQL | `performance_schema=OFF` | 本项目无细粒度性能分析需求，关闭以省内存；需要时可临时开启 |
| MySQL | **binlog 保持开启** | 保留按时间点恢复（PITR）能力；不再使用 `skip-log-bin` |
| Redis | `maxmemory=256mb`、`maxmemory-policy=allkeys-lru` | 500 用户规模的登录态 + 缓存总量在 10M 量级，256mb 余量充足 |
| backend | `-Xms512m -Xmx1g -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=128m -XX:+UseG1GC` | G1GC 适配 1G 以上堆；元空间与直接内存**必须显式设上限**，否则 native 内存无界 |
| backend | Tomcat `server.tomcat.threads.max=200`、Hikari `spring.datasource.hikari.maximum-pool-size=20` | **必须显式设置**。不写会依赖框架默认值，历史上"漏写上限"正是内存预算失控的根因 |
| nginx | 不限 | 静态资源 gzip 与缓存头 |

**JVM 参数的单一来源**：`JAVA_OPTS` 只在 `.env` 中定义一次，`docker-compose.yml` 引用它，**技术方案与各 ADR 一律不复述具体数值**。v1.3 曾出现 `deployment.md` 漏写 `-XX:MaxDirectMemorySize` 而技术方案写了的漂移；v1.4 起以本条为修复手段。

---

## 3. 本地开发

1. **前置**：**JDK 21**（见 §3.1）、Node.js 18+、Docker Desktop（用于 Redis 与测试容器）。
2. **中间件**（依据本机实测环境）：
   - **MySQL 用 Windows 原生实例**（8.0.34，已在 3306 运行）：开发库 `hy_forum`、测试库 `hy_forum_test`。
     **不要**再用容器占 3306。
   - **Redis 用 VM 内的容器**（本机 Windows 无 Redis）：`192.168.100.128:6380`，容器 `hy-redis`，见 §1.1。
     后端开发配置连接串指到这里即可，**不需要在 Windows 装 Docker Desktop**。
   - 若将来更想完全容器化：把容器 MySQL 映射到 **3307** 并同步改 `.env`；**不要**为了容器化去停掉原生 MySQL（M0 的数据地基在它上面）。
3. **测试库**：集成测试连 `hy_forum_test`，**每次跑测试前重建一次**以保证起点干净：
   ```
   powershell -File scripts/init_test_db.ps1 -User root -Password '***'
   ```
   该脚本的 DDL **从 `docs/db/schema.sql` 派生**（只做库名替换），**不维护第二份建表脚本** —— 否则测试库与生产库会各自漂移。取舍说明见 [`../testing/README.md`](../testing/README.md) §3。
4. **后端**：IDE 直接运行，或 `mvn spring-boot:run`。前端：`npm run dev`（Vite 代理 `/api` 到后端）。
5. **图片存储（开发期）**：两种方式，**都必须走服务端签名直传**（`AGENTS.md` 铁律 8）：
   - 推荐：本地 **MinIO**，S3 兼容、支持 presigned 签名与回调，离线可用；
   - 或使用阿里云 OSS 的**测试 Bucket**（注意隔离，不要与生产 Bucket 混用）。
   > 无论哪种，前端**绝不持有 AccessKey**：签名一律由后端下发，与生产一致。开发期"图省事"用固定密钥直传会破坏铁律 8 的验证路径。
6. **受限项**：备案、域名、HTTPS、公安备案、网信备案**在开发期均不需要**（用 `localhost`，必要时用 `http`），上线前按 §4 处理。
7. **红线**：本地不受内存约束，但**不得因此写出"只有本地能跑"的代码** —— §5 的性能实践在开发期同样成立。

### 3.1 JDK 21（已装好，勿再引入第二个版本）

**当前状态（2026-09-14 实测）**：

| 项 | 值 |
|---|---|
| 版本 | **Temurin 21.0.12.1+1 LTS**（`javac 21.0.12.1`、`jcmd` 齐备） |
| 位置 | `D:\develop\jdk21` |
| `JAVA_HOME`（用户级） | `D:\develop\jdk21` |
| `PATH`（用户级） | 最前插入 `D:\develop\jdk21\bin` |
| 机器级 PATH | 仍含 `C:\jdk-25.0.2\bin`（**未改动**；用户级 PATH 在有效 PATH 中靠前，故被压过） |
| 回退备份 | `D:\develop\JDK21-环境变量-改动前备份.txt`（含原值与回退命令） |

> **原问题**：`JAVA_HOME` 指向 `jdk17`、而 PATH 上的 `java` 是 **25.0.2**，两者不一致 —— Maven 用 `JAVA_HOME`、命令行 `java` 用 PATH，会产生"`mvn` 编译通过但 `java -jar` 报错"这类极难定位的问题。Java 25 还**超出 Spring Boot 3.3.x 支持范围**（ByteBuddy / ASM 对新 class 版本常有兼容问题）。现已统一到 21。

> **两个 Windows 特有的坑（都已实测复现）**：
> 1. **已运行的进程不会自动获得新环境变量** —— 若某个 shell / IDE 是在改环境变量之前启动的，它仍是旧 `JAVA_HOME`。此时 Maven 用 17 编译，`<release>21</release>` 会报 `invalid target release: 21`。**重启该 shell / IDE 即可**；临时办法是在命令前显式 `$env:JAVA_HOME='D:\develop\jdk21'`。
> 2. **控制台中文乱码**：JVM 的 `stdout.encoding` 跟随系统代码页（**GBK**），而 `file.encoding` 是 UTF-8 → Java 应用的中文日志会乱码。修法：`java '-Dstdout.encoding=UTF-8' -jar ...`，**参数必须整体加引号**（否则会被 PowerShell 拆成主类名，报 `ClassNotFoundException`）。

**验证方式**（[`scripts/toolchain-check/`](../../scripts/toolchain-check) 是专门为此建的独立小 Maven 工程）：

```powershell
cd scripts\toolchain-check
mvn -B --no-transfer-progress clean package
java '-Dstdout.encoding=UTF-8' -jar target\toolchain-check-1.0.0.jar
# 期望输出 RESULT: PASS；退出码 0
```

它做的是**反向验证**：运行时若不是 21.x 就退出码非 0 —— 因此不会被"悄悄退回 17"骗过。该预检已实测通过（构建 15.7 秒，本地仓库已落 606 MB）。

---

## 4. 上线（升配后）

### 4.1 上线前的必要动作（按顺序）

1. **确定服务器规格**并回填本节；按 §6 复算 §2 参数，**回填本文档**（含容器上限与实测值）。
2. 决定现有 2C2G 实例的去向：续费／变配／退订（到期 **2027-01-06**）。
3. 域名注册 + ICP 备案 + 公安联网备案 + 论坛社区备案咨询 —— **这是最大时间瓶颈，本应在 M0 起并行启动**，详见 `docs/PLAN.md` §3 与 §6。
4. 完成 `docs/PLAN.md` §6 的待确认事项（含带宽规格，决定图片是否必须上 CDN）。

### 4.2 部署步骤

1. 服务器初始化：创建普通用户、禁用 root 远程登录、配置 SSH 密钥。
2. 安装 Docker Engine 与 Docker Compose Plugin。
3. **在本地或 CI 构建产物**：`hy-forum.jar` 与前端 `dist/`，并构建 / 导出 backend 与 nginx 镜像。
   **绝不在服务器上构建**（铁律 6）。镜像分发方式（`docker save`／`load` 或镜像仓库）必须在部署前确定，否则本步骤无法执行。
4. 上传产物、`docker-compose.yml`、`nginx.conf` 与 `.env`。
5. `docker compose up -d`。
6. 配置 Nginx 与 HTTPS（阿里云免费 DV 证书或 Let's Encrypt 自动续期）。
7. 配置 MySQL 每日 `mysqldump` 备份并上传至 OSS，保留 7 天；**并验证过一次真实恢复**。
8. 配置日志轮转：**访问日志保留 ≥ 6 个月**（不写 180 天 —— 跨 31 天的月份 180 天不足 6 个月）；**应用日志保留 30 天，仅用于排障，不承担合规留痕**。
9. 配置 `admin_operation_log`（审核留痕）的留存策略：**只增不改不删**，仅按 `created_at` 滚动清理 ≥ 6 个月之前的数据；确认后台全部写操作已接入留痕（合规 C9，见技术方案 §6.11、§10）。
10. 配置内存与磁盘告警（含容器 `memory.events` 与 Redis `evicted_keys`）；验证 `docker stats` 与 `free -m` 在正常水位。
11. 若目标规格内存紧张（可用内存 < 4 GiB，例如最终仍用 2 GiB），**必须**先执行附录 A 的全部条款（swap、容器上限、JVM 参数整体替换为附录 A 的那一套）。

---

## 5. 应用层性能实践（与规格无关，一律遵守）

- 列表接口**只返回摘要**，不返回正文全文。
- 分页 `size` **硬上限 20**。
- 列表页图片一律使用 `thumb_url`（由 OSS 图片处理参数生成），**禁止加载原图**。
- 首屏不发起并发请求；版块、用户简要信息走缓存接口。
- 只保留 `/actuator/health`，关闭其余 Actuator 端点；**生产环境必须关闭 Knife4j / springdoc 调试页**（评审遗留项 P1-8）。
- Tomcat `threads.max` 与 Hikari `maximum-pool-size` 显式设置，**禁止依赖框架默认值**（见 §2）。
- 生产环境日志级别为 `INFO`，**禁止 `DEBUG`**。
- 图片必须走 OSS + CDN（ECS 带宽不适合直接对外提供图片流量），开启防盗链与流量告警。

---

## 6. 参数复算触发条件

出现以下任一情况，**必须重新核算并回填 §2**：

- **上线目标规格确定或变更时**（强制）
- 内存使用率连续 3 天超过 85%
- 日活跃用户超过 200
- 系统日志或 `docker events` 中出现容器 OOM / OOM Killer 记录
- 接口 P95 持续超过 800ms（技术方案 §11 的指标是 500ms，800ms 是"已劣化、需重新评估容量"的触发线）

---

## 7. 部署检查清单

### 7.1 开发期

- [ ] `docker compose up -d mysql redis` 可正常启动，参数取 §2
- [ ] 后端可本地启动，前端 `npm run dev` 可代理到后端
- [ ] 图片上传走**服务端签名直传**（MinIO 或 OSS 测试 Bucket），前端代码中**无任何 AccessKey**
- [ ] 全链路（注册 → 登录 → 发帖带图 → 评论 → 收藏）在本地可跑通

### 7.2 上线前（规格回填后）

- [ ] 目标服务器规格已回填 §1，§2 全部容量参数**已按 §6 复算并回填**
- [ ] 各容器已设置 `mem_limit`，与 §2／附录 A 一致
- [ ] JVM 参数由 `.env` 的 `JAVA_OPTS` **单一来源**注入（技术方案与 ADR 中无复述数值）
- [ ] Tomcat `threads.max`、Hikari `maximum-pool-size` 已显式生效，非框架默认值
- [ ] backend 容器内已用 `jcmd <pid> VM.metaspace` 实测元空间占用并回填参数表
- [ ] 若目标内存 < 4 GiB：附录 A 的 swap、`vm.swappiness`、`memswap_limit` 条款全部执行
- [ ] 构建产物来自本地 / CI，服务器上无构建工具链产出；**镜像分发方式已落实**
- [ ] HTTPS 证书有效期与自动续期已确认
- [ ] 数据库每日备份已跑通并**验证过恢复**（含 `admin_operation_log` 表）
- [ ] 日志轮转已配置：访问日志 ≥ 6 个月，应用日志 30 天
- [ ] `admin_operation_log` 留存策略已配置（只增不改不删，保留 ≥ 6 个月）
- [ ] 生产环境 Knife4j / springdoc 调试页已关闭
- [ ] 内存与磁盘告警已接入（含容器 cgroup OOM 事件与 Redis `evicted_keys`）
- [ ] 备份策略已写明 **RPO / RTO**（评审遗留项 P1-10）

---

## 附录 A：若最终目标仍为 2 vCPU / 2 GiB

> **本附录不是档位，而是"极端规格下的应急参数集"。** 默认不启用；仅当 §4.2 第 11 条的条件成立（目标内存 < 4 GiB）时，用本节参数**整体替换** §2 中对应项。
> 本节的数字在 v1.4 评审中**按进程峰值重新核算过**（v1.3 的原值不闭环，见 A.2）。

### A.1 参数

| 用途 | 容器上限 | 关键配置 |
|---|---|---|
| 系统 + Docker 守护进程 | ~350M | 不设容器限制，作为预留 |
| MySQL | 512M | `innodb_buffer_pool_size=128M`、`max_connections=24`、`performance_schema=OFF`、`skip-log-bin` |
| Redis | 96M | `maxmemory=48mb`、`maxmemory-policy=allkeys-lru`、`save 900 1` |
| backend | 800M | `-Xms256m -Xmx384m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=48m -Xss512k -XX:+UseSerialGC`；Tomcat `threads.max=60`、Hikari `maximum-pool-size=8` |
| nginx | 64M | |
| **合计** | **约 1822M / 2048M** | **余量约 226M** |

### A.2 backend 800M 的构成（为什么不是 640M）

| 项 | 量 |
|---|---|
| Heap | 384M |
| Metaspace | 160M（**上线后必须 `jcmd` 实测回填**） |
| Direct memory | 48M |
| Code cache | ~48M |
| 线程栈 | ~41M（约 80 线程 × `-Xss512k`） |
| JVM / GC 结构 | ~30M |
| **小计** | **约 711M**（容器 800M 留约 89M 尖峰余量） |

v1.3 的原值（backend 640M / Redis 160M / 合计 1726M / 余量 320M）是「**容器上限相加**」而非「**进程峰值**」，有两处硬伤：

1. backend 640M **小于其自身 JVM 参数之和**（`-Xmx448m` + `MaxMetaspaceSize=128m` + `MaxDirectMemorySize=64m` = 640M），尚未含 code cache 与线程栈 → 并发上升必被 **cgroup OOMKill**。
2. 把 backend 抬到 896M 会让总预算变成 1982M，余量只剩 66M —— 即「余量 320M」**根本不存在**。

### A.3 必须同时满足的附加条款

- **必须配置 2G swap**，且设 `vm.swappiness=10`；各容器 `memswap_limit == mem_limit`（**禁止容器换出**）—— 放任 JVM / MySQL 换出会让 SerialGC 叠加秒级 STW，比 OOM 更难排查。
- 余量只有 226M，**任何新增常驻进程前都必须重新评估**。
- Redis 的 `evicted_keys` 必须纳入监控：`allkeys-lru` 会淘汰**任意键**，包括登录态 token（表现为用户莫名掉线）。
- 该规格属**临界可用**：低并发（20–30 并发 / QPS 50–100）下够用，但**没有冗余**。技术方案 §11 的并发指标即按此设定。

---

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v2.0 | 2026-09-14 | **结构性变更**：取消部署双档位，改为单一配置；基准由 2 vCPU / 2 GiB 改为本地开发；新增 §3 本地开发、§4 上线（升配后）流程；原 2C2G 参数核算降为附录 A。依据 D4 决策（见评审记录 §10）与 ADR-0010 |
| v1.4 | 2026-09-14 | 方案评审后按进程峰值重算 §2 内存预算、统一 JVM 参数单一来源、补齐 Tomcat/Hikari 上限、明确 swap 与容器换出策略、修订日志与留痕留存期 |
| v1.3 | 2026-09-14 | 按服务器实际规格（2 vCPU / 2 GiB）重写，引入「部署双档位」 |
