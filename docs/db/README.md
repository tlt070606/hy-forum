# 数据库设计规范

## 基线

- MySQL 8.0，InnoDB，字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`，时区 `Asia/Shanghai`
- 主键统一 `BIGINT UNSIGNED AUTO_INCREMENT`
- 软删除使用 `is_deleted TINYINT`，配合 MyBatis-Plus 逻辑删除
- 时间字段 `created_at` / `updated_at` 由数据库默认值维护
- **不使用外键约束**，关联关系由应用层保证（便于迁移与分库）

## 命名

- 表名、字段名：小写 + 下划线，**不加表前缀**
- 索引：唯一索引 `uk_`，普通索引 `idx_`，全文索引 `ft_`
- 约束：CHECK 约束 `chk_`（**MySQL 8.0.16+ 起真正强制执行**，不是只解析不生效）
- 布尔语义字段统一用 `TINYINT`（0/1），不用 `BOOLEAN`

## 计数冗余字段

`post_count`、`like_count`、`follow_count`、`fans_count` 等为**冗余字段**，用于列表页免聚合查询。

- 只做增删运算：`UPDATE ... SET like_count = like_count + 1`，禁止「先查后写」
- 与关系表（`post_like` 等）的写入在**同一事务**内完成
- 提供管理员可手动触发的**计数校准任务**，用于修复极端不一致

## 变更冻结流程

1. 先修改 [`schema.sql`](schema.sql)
2. 在本文件「变更登记」中登记：表、字段、原因、影响
3. 生成增量迁移脚本 `migrations/YYYYMMDD_description.sql`
4. 评审通过后才允许执行到数据库

**禁止**直接改数据库而不改 `schema.sql` —— 两者不一致即视为缺陷。

## 变更登记

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-09-14 | 新增 `sys_config`、`invite_code` 两张表 | 支撑注册模式配置与邀请制降级方案 |
| 2026-09-14 | 新增 `post_collect` 表 | 收藏功能的**关系数据**来源。v1.3 只有 `post.collect_count` 冗余计数与收藏接口，缺关系表 → M4「收藏」与 `GET /api/user/collections` 无数据来源（评审记录 P0-1） |
| 2026-09-14 | 新增 `admin_operation_log` 表 | 承载合规义务 C9 的审核操作留痕（管理员／时间／对象／动作／理由／IP），保留 ≥ 6 个月。原方案留痕无落点，且应用日志只保留 30 天，以其承担 C9 必然违规（评审记录 P0-2） |
| 2026-09-14 | `comment` 表新增 CHECK 约束 `chk_comment_two_levels` | 两层结构不变量（主楼 `(0,0)`、楼中楼 `(主楼id, 主楼id)`）的**机械保证**。评审遗留项 P1-3 定案：采用归并语义，使三层在数据上不可能被表达。**注意**：CHECK 只约束同一行内两列关系，跨行引用校验必须集中在唯一写入口，见 [`../技术方案.md`](../技术方案.md) §6.6 |

> 增量脚本：[`migrations/20260914_add_post_collect_and_admin_operation_log.sql`](migrations/20260914_add_post_collect_and_admin_operation_log.sql)、[`migrations/20260914_add_comment_two_level_check.sql`](migrations/20260914_add_comment_two_level_check.sql)。M0 尚未执行，全新环境直接执行 `schema.sql` 即可；已按旧版本建过库的环境执行对应迁移脚本。