# 部署方案（单一配置）

> 本文件是部署的**唯一依据**。
> 核心原则：**只维护一份构建产物与一份配置，不存在部署档位。**
> 版本 **v3.0**（2026-10-01）。**v3.0 是一次"回填现实"的修订**：v2.0 写于上线之前（2026-09-14），
> 假想的部署形态是 docker-compose + 域名 + HTTPS；而 **2026-09-24 的实际上线**（见
> [`上线记录-2026-09-24.md`](上线记录-2026-09-24.md)）最终采用的是 **systemd 裸机 + apt 原生 MySQL/Redis + Nginx 反代，
> 以 IP + HTTP 访问**。v3.0 把 §2/§4/§7 按**线上真实参数与步骤**重写，消除"唯一依据说假话"的状态
> （此前 §2 的 MySQL 512M/Redis 256mb/-Xmx1g 与线上真实的 64M/64mb/256m 全部不符，
> 真实参数只活在 `scripts/deploy/*.sh` 的 heredoc 里 —— 那正是本文件失职的地方）。
>
> **演示站裁定（需求方 2026-10-01，路线 A）**：本站定位为**个人作品集/练习站点**，
> **不做域名注册与 ICP 备案，不上 HTTPS**，以 `http://8.138.237.212/` 访问。
> 因此：**本站只接受演示数据，任何输入的口令都应视为经明文链路传输（视同公开）**。
> 该裁定对应的**代码级补偿**已落地（见 §2.1）；由此放弃的保障与残余风险登记在 `docs/PLAN.md` 风险登记册 R2。
> 决策依据：[`../adr/0010-部署以本地开发为基准并取消档位.md`](../adr/0010-部署以本地开发为基准并取消档位.md)（D4）。**本文档取代 [`../adr/0004-部署双档位.md`](../adr/0004-部署双档位.md)。**

---

## 0. 设计原则

1. **单一产物、单一配置**：一份 JAR、一份前端 `dist/`、一套环境变量。不存在档位文件，也不存在 `APP_PROFILE` 开关。
2. **本地优先**：开发期以本地运行为准，**不预先用低配内存约束限制设计与实现**。理由：先证明「功能与性能可以做到」，再在真实规格上做取舍；反过来（先按最低配写死）会把大量精力花在微调上，且容易掩盖真正的性能问题。
3. **上线前一次性定规格**：上线前必须先回填目标服务器规格，并按 §6 复算参数、回填 §2。**不允许"拿一份来路不明的参数直接上生产"**。
4. **不预先自我设限，但不放弃自觉**：不限内存 ≠ 可以写低效代码。§5 的性能实践与规格无关，一律遵守。

> **与铁律的关系**：`AGENTS.md` 铁律 2（零代码分叉）在单一配置下**自动满足**（只有一份产物、一份配置）；铁律 6（禁止在服务器上构建）**继续有效**，其理由是构建期内存峰值（`mvn package` / `npm run build` 均超 1G）会打挂低配服务器，与档位机制无关。

---

## 1. 目标环境

| 阶段 | 环境 | 内存约束 | 状态 |
|---|---|---|---|
| **开发期** | 本地开发机 | **不设约束** | 持续 |
| **测试 / 演示** | 本地或局域网 | 不设约束 | 可选 |
| **线上** | **阿里云 2 vCPU / 2 GiB（未升配，直接上线）** | 见 §2 线上真实参数 | **2026-09-24 已上线**：`http://8.138.237.212/` |

现有服务器为阿里云 **2 vCPU / 2 GiB**、Ubuntu 22.04、ESSD 40 GiB、公网 IP 8.138.237.212、到期 **2027-01-06**。
按 2026-09-24 的实际上线决策，**未升配、未换机，就在这台 2C2G 上以裸机 systemd 形态上线**（v2.0 曾计划"升配后再上线"，未执行；是否续费/变配，到期前决定）。

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

## 2. 线上真实参数（2026-09-24 上线实测，v3.0 回填）

> 下表是**线上正在生效的参数**，与 `scripts/deploy/install_stack.sh`、`finish_setup.sh`、`finish_deploy.sh` 中的 heredoc 一一对应 —— **改参数必须两处同步**（脚本 + 本表），否则视为缺陷。
> 开发期不受这些约束（见 §3）；附录 A 保留为"若重装/换机仍为 2 GiB"的核算依据。

| 组件 | 参数 | 说明 |
|---|---|---|
| MySQL（apt 原生） | `innodb_buffer_pool_size=64M` | 1000 条量级数据集够用；`max_connections` 走默认 151（Hikari 只用 10） |
| MySQL | `bind-address=127.0.0.1`；应用账号限 `hyforum@127.0.0.1`；口令 600 权限文件 | 数据库不对公网暴露 |
| Redis（apt 原生） | `maxmemory=64mb`、`maxmemory-policy=allkeys-lru`、`bind 127.0.0.1` | ⚠️ **登录态 token 与业务缓存同实例**：内存压力下 LRU 可能逐出 token（表现：用户掉线）。`evicted_keys` 要盯着（附录 A.3 同款风险） |
| backend（systemd） | `-Xms128m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=48m`（真实值以 `finish_deploy.sh` 的 systemd 单元为准） | 2 GiB 裸机预算内的取值 |
| backend | Tomcat / Hikari 用代码内默认值（Hikari `maximum-pool-size=10`） | |
| nginx | `client_max_body_size 20m`；`/api/` 反代 `127.0.0.1:8080`；静态直出 `/var/www/hy-forum` | 配置全文见 `scripts/deploy/finish_deploy.sh` |
| 备份 | cron 每天 03:30 `mysqldump` → `/var/backups/hy-forum`，保留 7 天；产物做**大小 + 可解压**双重校验 | 2026-10-01 修复：此前 `mysql`（交互客户端）被误当导出工具用，备份是 20 字节空文件（上线记录 §5）；修复后每次运行自校验，失败退出码非 0 |

### 2.1 演示站裁定的代码级补偿（2026-10-01，路线 A）

全站 HTTP 明文（见文首裁定）接受的残余风险，用以下已在代码层落地的措施封顶损失：

| 措施 | 落点 |
|---|---|
| 限流取 IP 只信 Nginx 覆盖写入的 `X-Real-IP`，不读可伪造的 `X-Forwarded-For`（否则限流可一击绕过） | `AuthController#clientIp`、`AdminAuthController#clientIp` |
| 管理员登录 IP 限流（此前是全站唯一无限流的认证端点，可在线爆破） | `hy.rate-limit.admin-login-per-minute`（默认 10/分钟） |
| 发表评论用户限流（此前发帖有限流、评论没有） | `hy.rate-limit.comment-per-hour`（默认 20/小时，§8.7 原文） |
| token 有效期 7 天 → **2 天**（被窃听后的损失窗口封顶） | `application.yml` sa-token.timeout |
| 备份产物自校验（空备份即失败） | `scripts/deploy/install_backup_and_check.sh` |

**未落地（登记为待办，需要服务器操作权限时执行）**：Nginx 对 `/api/admin/` 加 IP 白名单或仅允许 SSH 隧道访问 —— 管理员 token 走 HTTP 是全站最值钱的窃听目标，白名单能把这个面收窄到管理员自己的出口 IP。

```nginx
# 待应用（加入 /etc/nginx/sites-available/hy-forum 的 server 块，location /api/ 之前）：
# location ^~ /api/admin/ {
#     allow <管理员出口IP>;   # 动态 IP 场景改用 WireGuard/SSH 隧道
#     deny all;
#     proxy_pass http://127.0.0.1:8080;
#     include /etc/nginx/proxy_params_hy.conf;  # 与 /api/ 同款 proxy_set_header
# }
```

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

## 4. 线上部署（实际形态：systemd 裸机，无 Docker）

> 2026-09-24 上线时未采用 v2.0 设想的 docker-compose 路线（全仓库不存在任何 compose 文件/Dockerfile），
> 改用 **apt 原生 MySQL/Redis + systemd 跑 jar + Nginx 反代**。步骤以 `scripts/deploy/` 为准：

### 4.1 首次装机（`install_stack.sh` + `finish_setup.sh`）

1. apt 安装 openjdk-21、mysql-server、redis-server、nginx；配置 2G swap。
2. MySQL：`bind-address=127.0.0.1`、`innodb_buffer_pool_size=64M`、建 `hy_forum` 库与应用账号（口令随机、600 权限文件）。
3. Redis：`maxmemory=64mb` + `allkeys-lru` + 仅本机。

### 4.2 每次发布（本机构建 → 上传 → 重启；**铁律 6：服务器上零构建**）

```bash
# 本机：
mvn -DskipTests package                 # 产物 server/target/*.jar
cd web && npm run build:h5              # 产物 web/dist/
cd admin-web && npm run build           # 产物 admin-web/dist/（M6 管理后台，base=/admin/）
scp server/target/hy-forum-*.jar myserver:/opt/hy-forum/app.jar
tar -czf h5.tar.gz -C web dist && scp h5.tar.gz myserver:/root/
tar -czf admin.tar.gz -C admin-web dist && scp admin.tar.gz myserver:/root/
# 服务器：
mkdir -p /var/www/hy-forum/admin
tar -xzf /root/admin.tar.gz -C /var/www/hy-forum/admin --strip-components=1
systemctl restart hy-forum              # systemd 单元见 finish_deploy.sh 的 heredoc
```

**管理后台的 Nginx 路由**（M6）：`location /admin/` 独立直出 `/var/www/hy-forum/admin`
（配置在 `finish_deploy.sh` 的 nginx heredoc 内）。管理端用 hash 路由，无 SPA 回退依赖。
访问地址 `http://8.138.237.212/admin/`；建议按 §2.1 对 `/api/admin/` 加 IP 白名单时
把 `/admin/` 一起圈进去。

健康检查：`curl http://8.138.237.212/api/feed`（或按 `finish_deploy.sh` 末尾的自检清单）。

### 4.3 遗留待办

- ~~每日备份~~ 已装（`install_backup_and_check.sh`，2026-10-01 修复并加产物校验）；**恢复演练仍未做过**。
- 生产关闭 Knife4j / springdoc 调试页（评审 P1-8，**未做**）：当前 `/v3/api-docs`、`/swagger-ui.html` 公网可匿名访问。
- `/api/admin/` IP 白名单（见 §2.1）。
- 日志轮转与 `admin_operation_log` 留存策略（§7.2）仍为纸面项。

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

- [x] MySQL（Windows 原生 3306）与 Redis（VM 6380）可用，参数见 §3
- [x] 后端可本地启动，前端 `npm run dev` 可代理到后端
- [x] 图片上传走**服务端签名直传**（OSS），前端代码中**无任何 AccessKey**
- [x] 全链路（注册 → 登录 → 发帖带图 → 评论 → 收藏）在本地可跑通

### 7.2 上线核对（2026-09-24 实际执行情况）

- [x] 服务器规格与 §2 一致（2C2G 未升配，参数按附录 A 口径收紧）
- [x] 构建产物来自本地（jar + H5 tar），服务器上无构建工具链产出（铁律 6）
- [x] ~~HTTPS 证书~~ → **不适用**（演示站裁定，见文首）
- [x] 数据库每日备份 cron 已装、产物自校验已通过（2026-10-01 修复后）
- [ ] **备份恢复演练仍未做过**（P1-10 的 RPO/RTO 亦未写明）
- [x] Tomcat / Hikari 上限显式（Hikari 10）
- [ ] 日志轮转：访问日志 ≥ 6 个月，应用日志 30 天 —— **未配置**
- [ ] `admin_operation_log` 留存策略（只增不改不删，≥ 6 个月）—— **未配置**
- [ ] 生产环境 Knife4j / springdoc 调试页**未关闭**（P1-8，公网可匿名访问）
- [ ] `/api/admin/` IP 白名单 —— **未应用**（§2.1 有现成配置）
- [ ] 内存告警 / Redis `evicted_keys` 监控 —— **未配置**（2 GiB + allkeys-lru 下这条是实风险）

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
| **v3.0** | **2026-10-01** | **回填上线现实 + 演示站裁定**：§1/§2/§4/§7 按线上真实形态（systemd 裸机、IP+HTTP、真实内存参数）重写，消除与 2026-09-24 上线事实的漂移；文首记入需求方"演示站、不做域名/HTTPS"裁定（路线 A）与五项代码级补偿；备份脚本修复记录入 §2。v2.0 假想的 compose 路线作废 |
| v2.0 | 2026-09-14 | 结构性变更：取消部署双档位，改为单一配置；基准由 2 vCPU / 2 GiB 改为本地开发；新增 §3 本地开发、§4 上线（升配后）流程；原 2C2G 参数核算降为附录 A。依据 D4 决策（见评审记录 §10）与 ADR-0010 |
| v1.4 | 2026-09-14 | 方案评审后按进程峰值重算 §2 内存预算、统一 JVM 参数单一来源、补齐 Tomcat/Hikari 上限、明确 swap 与容器换出策略、修订日志与留痕留存期 |
| v1.3 | 2026-09-14 | 按服务器实际规格（2 vCPU / 2 GiB）重写，引入「部署双档位」 |
