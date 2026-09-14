# Hy论坛

中文综合论坛，含「资源分享」版块（发帖可附带网盘链接与提取码）。

- **交付形态**：网站（Vue 3 SPA + PWA）+ 安卓 APK（WebView 壳）
- **不包含**：微信小程序
- **后端**：Java 21 + Spring Boot 3（模块化单体）
- **存储**：MySQL 8 + Redis 7 + 阿里云 OSS
- **当前状态**：方案阶段（v1.6）。已完成首轮评审：3 处 P0 已修订、P1-1／P1-2／P1-12 已整改；部署改为**单一配置**（本地开发基准，上线先升配）。尚未开始编码

## 文档入口

| 想了解 | 去哪里 |
|---|---|
| 协作铁律与准入 | [`AGENTS.md`](AGENTS.md) |
| 范围、里程碑、验收、风险 | [`docs/PLAN.md`](docs/PLAN.md) |
| 完整技术设计 | [`docs/技术方案.md`](docs/技术方案.md) |
| 部署（单一配置） | [`docs/ops/deployment.md`](docs/ops/deployment.md) |
| 建表脚本 | [`docs/db/schema.sql`](docs/db/schema.sql) |
| 关键决策与理由 | [`docs/adr/`](docs/adr/) |
| 方案评审结论与遗留项 | [`docs/review/`](docs/review/) |
| AI 协作规范与协作看板 | [`docs/agents/`](docs/agents/) |
| 测试策略与验收项对照表 | [`docs/testing/`](docs/testing/) |
| 全部文档索引 | [`docs/README.md`](docs/README.md) |