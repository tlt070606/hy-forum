# Hy论坛

中文综合论坛，含「资源分享」版块（发帖可附带网盘链接与提取码）。
**两周内从零做到公网上线**（2026-09-14 开工 → 2026-09-24 上线），已部署于 http://8.138.237.212/ （演示站）。

> **这个仓库是什么**：一个全栈练手 + 作品集项目。定位是「演示站」——不做域名/HTTPS/备案，只接受演示数据（该取舍见 [`docs/PLAN.md`](docs/PLAN.md) 风险登记册 R2，是显式裁定而不是疏忽）。

- **前端**：uni-app（Vue 3 + Vite + TS 严格模式 + wot-design-uni），一份代码编译三端 —— H5 网站 + 安卓 App + 微信小程序（小程序仅练习，受个人主体与域名白名单约束不上线，见 [`docs/adr/0011`](docs/adr/0011-前端改用uni-app作为唯一前端.md)）
- **后端**：Java 21 + Spring Boot 3.3，**模块化单体**（8 个业务包互不依赖，只经 `common`/`domain` 通信，由 ArchUnit 机器守门）
- **存储**：MySQL 8（16 表，逻辑外键 + CHECK 约束）+ Redis 7 + 阿里云 OSS（服务端签名直传 + RSA 回调验签）

## 功能

注册/登录（图形验证码、注册模式运行期切换、邀请码）、版块与帖子（九宫格图片、网盘卡片、链接归一化）、两层楼中楼评论、点赞/收藏/关注、个人主页、首页双流（最新/关注）、消息通知、举报与后台审核队列（帖子+评论）、管理操作留痕、发帖/评论/登录多维度限流。

## 工程上比较在意的几件事

| 领域 | 做法 |
|---|---|
| **架构守卫** | 铁律（禁跨模块依赖、禁重组件、禁硬编码密钥）不靠自觉：ArchUnit 按包判依赖 + 解析 Maven 依赖树查重组件 + pre-commit 钩子与 CI 扫密钥（全 git 历史 0 泄漏）。业务包名单从编译产物自动推导，新增包自动入守卫 |
| **并发正确性** | 计数全部原子维护（无读改写）：浏览量走 Redis INCR + 5 分钟 SCAN/GETDEL 批量回写；点赞/收藏用 `DuplicateKeyException` 幂等 + 条件 UPDATE；邀请码消费防并发重用。jqwik 属性测试随机操作序列后断言计数等式恒成立 |
| **契约管理** | 后端 OpenAPI 脚本导出为唯一契约出口，CI 重导比对防漂移；前端 `contract.ts` 登记端点与响应形状断言，契约演进 8→36 路径全程留痕 |
| **测试** | 53 个测试文件（约 1.16 万行，为主代码的 77%）：真 MySQL/真 Redis 集成测试、真并发测试（20 线程点赞幂等）、jqwik 属性测试、schema 不变量测试（CI 用 information_schema 断言 16 表基线） |
| **OSS 直传** | 后端签名（policy 锁目录/大小/类型）→ 前端直传 → RSA 验签回调（公钥域名白名单、防重放）→ 内容二次校验。前端零密钥 |
| **诚实的取舍** | 全库 0 个 TODO：欠账一律登记为 CR/裁定（含「评论数只计可见评论」「演示站不上 HTTPS」这类产品决策），每条附理由与落地位置 |

## 本地运行

```bash
# 后端（需 JDK 21、MySQL 8、Redis）
cd server && mvn spring-boot:run
# 前端（H5，Vite 代理 /api 到后端）
cd web && npm i && npm run dev
```

数据库基线：[`docs/db/schema.sql`](docs/db/schema.sql)（一次执行即建全库）。

## 文档入口

| 想了解 | 去哪里 |
|---|---|
| 协作铁律与准入 | [`AGENTS.md`](AGENTS.md) |
| 范围、里程碑、验收、风险 | [`docs/PLAN.md`](docs/PLAN.md) |
| 完整技术设计 | [`docs/技术方案.md`](docs/技术方案.md) |
| 部署（线上真实形态） | [`docs/ops/deployment.md`](docs/ops/deployment.md) |
| 建表脚本 | [`docs/db/schema.sql`](docs/db/schema.sql) |
| 关键决策与理由 | [`docs/adr/`](docs/adr/) |
| 方案评审结论与遗留项 | [`docs/review/`](docs/review/) |
| AI 协作规范与协作看板 | [`docs/agents/`](docs/agents/) |
| 测试策略与验收项对照表 | [`docs/testing/`](docs/testing/) |
| 全部文档索引 | [`docs/README.md`](docs/README.md) |
