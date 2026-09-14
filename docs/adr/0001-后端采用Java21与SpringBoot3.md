# ADR-0001：后端采用 Java 21 + Spring Boot 3

- 状态：已接受
- 日期：2026-09-14

## 背景

需求方明确指定后端使用 Java。需要在单体框架中做出选择。

## 决策

- JDK 21（LTS）
- Spring Boot 3.3.x
- MyBatis-Plus 3.5.x 作为持久层
- Sa-Token 作为鉴权框架（前台 / 后台各一套 StpLogic）
- Knife4j（springdoc-openapi）提供接口文档

## 理由

1. Spring Boot 3 要求 JDK 17+，21 是当前 LTS，生命周期最长。
2. MyBatis-Plus 在单表 CRUD 上几乎零代码，贴合本项目以单表查询为主的场景；相比 JPA 更贴合国内生态与 SQL 可控性要求。
3. Sa-Token 比 Spring Security 轻量，原生支持 Redis 存储登录态，且能同时维护前台与后台两套独立登录体系。
4. 单体架构足够支撑 1000–10000 条数据、20–30 并发的规模，引入微服务属过度设计。

## 后果

- 部署时需关注 JVM 内存占用。在 2 GiB 服务器上必须显式设置**堆、元空间与直接内存**三项上限，否则默认堆会超出可用内存。**具体参数以 [`../ops/deployment.md`](../ops/deployment.md) §1 为唯一来源，本 ADR 不复述数值**（v1.3 曾在多处复述参数，导致本文档与 deployment.md、技术方案之间出现漂移）。
- v1.4 修订：原 `-Xmx448m` + `MaxMetaspaceSize=128m` 的组合，使 backend 容器上限（640M）**恰好等于 JVM 参数之和**，尚未计入 code cache 与线程栈，并发上升会被 cgroup OOMKill。已按进程峰值重算，并新增 Tomcat `threads.max` 与 Hikari 池上限两项必须外置的配置。
- MyBatis-Plus 的逻辑删除、分页插件需显式配置。