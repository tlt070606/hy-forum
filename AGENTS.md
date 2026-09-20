# AGENTS.md

本文件是 AI agent 与协作者的准入说明。开始任何工作前必须阅读。

## 项目定位

Hy论坛 —— 中文综合论坛，含资源分享版块（发帖可附网盘链接与提取码）。
交付形态：**uni-app 唯一前端，编译出三端** —— 网站（H5）+ 安卓 App + **微信小程序（仅练习，不上线）**。
后端：Java 21 + Spring Boot 3，模块化单体。

> 前端与交付形态的决策见 [`docs/adr/0011`](docs/adr/0011-前端改用uni-app作为唯一前端.md)。
> **小程序端有两条硬约束使它在可预见将来无法上线**（个人主体不支持 `web-view`、`request` 只允许已备案 HTTPS 域名），需求方已知并接受。**不要把它当"将来能上线的产品"投入精力。**

## 铁律

1. ~~**文档先行**~~ → **2026-09-20 需求方裁定：降级为「建议」**（原文：先改文档再改代码）。**理由**：这条在实际执行中从未成立 —— 复制格式、`§8.7` 措辞、CR-007 响应体三处都是"实现先改、文档后补、靠人发现"。**一条不被执行的铁律比没有更坏**：它让人以为有约束力。`docs/` 仍是唯一事实来源这一条**不变**；变更后的**补救通道**见 `web/src/api/contract.ts` 顶部的「契约变更公告栏」。建议：`docs/` 是唯一事实来源；本文件与 `docs/PLAN.md` 冲突时以 `docs/PLAN.md` 为准。
2. **零代码分叉**：低配档位与高性能档位必须共用同一份构建产物，差异**只允许**出现在 `.env` 与 compose override 文件。
3. **禁止跨模块调用**：业务模块之间只允许依赖 `hy-common` 与 `hy-domain`，禁止调用其他模块的 Mapper 或 Service 实现。
4. **禁止跨模块建表改表**：schema 变更必须走 `docs/db/README.md` 的冻结流程，先改 `docs/db/schema.sql` 再改库。
5. **禁止硬编码密钥**：AccessKey、密钥、域名一律走环境变量。APK 可被反编译，**前端只允许使用服务端签名**，绝不持有 AccessKey。
6. **禁止在服务器上构建**：`mvn package` 与 `npm run build` 内存峰值均超过 1G，2 GiB 服务器会 OOM。
7. **禁止在低配档位引入重组件**：Elasticsearch、RabbitMQ、Kafka、服务注册中心一律不用。
8. **文件上传必须走 OSS 服务端签名直传**，禁止后端中转。
9. 上述规则由代码评审与 ArchUnit 单测共同保证。

## 开工前必须确认的四份文档

- [`docs/PLAN.md`](docs/PLAN.md) 范围与验收标准
- [`docs/db/README.md`](docs/db/README.md) 表设计规范
- [`docs/db/schema.sql`](docs/db/schema.sql) 表结构基线
- [`docs/ops/deployment.md`](docs/ops/deployment.md) 部署档位与约束

**四份缺一份就不要开工。**

## 提交规范

Conventional Commits：`type(scope): 描述`
type 取 `feat` `fix` `docs` `refactor` `test` `chore`；scope 用模块名。
示例：`feat(post): 支持资源版块网盘链接归一化`

## 分支

`main` 保持可发布，功能开发走 `feature/<module>-<short-desc>`。