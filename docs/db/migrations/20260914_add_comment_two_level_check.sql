-- =============================================================
-- 增量迁移脚本  20260914_add_comment_two_level_check.sql
-- 对应变更：docs/db/README.md「变更登记」2026-09-14（comment CHECK 约束）
-- 对应基线：docs/db/schema.sql 表 5（comment）中的 chk_comment_two_levels
-- 依据：评审遗留项 P1-3 定案（见 docs/adr/0009 与 docs/review/2026-09-14-方案评审记录.md）
--
-- 适用场景：
--   * 全新环境 → 直接执行 docs/db/schema.sql（已含本约束），**不要**执行本脚本
--   * 已建过 comment 表的环境 → 执行本脚本
--
-- 前置条件（必须先满足，否则 ALTER 会失败）：
--   comment 表中不得存在违反下述不变量的历史数据。
--   检查语句见文件末尾的「迁移前自检」。
--
-- MySQL 版本要求：8.0.16+（该版本起 CHECK 约束真正强制执行；
--   8.0.15 及以前只解析不生效 —— 请勿在生产上使用低于 8.0.16 的版本）
-- =============================================================

USE hy_forum;

-- 不变量：主楼 (parent_id=0, root_id=0)；楼中楼 (parent_id<>0 且 parent_id=root_id)
ALTER TABLE `comment`
  ADD CONSTRAINT `chk_comment_two_levels`
  CHECK ((`parent_id` = 0 AND `root_id` = 0) OR (`parent_id` <> 0 AND `parent_id` = `root_id`));

-- ---------------------------------------------------------------
-- 迁移前自检（执行 ALTER 之前先跑这段，输出必须为 0 行）
-- ---------------------------------------------------------------
-- SELECT * FROM comment
--  WHERE NOT ((parent_id = 0 AND root_id = 0) OR (parent_id <> 0 AND parent_id = root_id));

-- ---------------------------------------------------------------
-- 迁移后验证（输出应显示 VALIDATED 状态）
-- ---------------------------------------------------------------
-- SELECT tc.CONSTRAINT_NAME, tc.ENFORCED, cc.CHECK_CLAUSE
--   FROM information_schema.TABLE_CONSTRAINTS tc
--   JOIN information_schema.CHECK_CONSTRAINTS cc
--     ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
--  WHERE tc.TABLE_SCHEMA = 'hy_forum'
--    AND tc.TABLE_NAME = 'comment'
--    AND tc.CONSTRAINT_TYPE = 'CHECK';

-- ---------------------------------------------------------------
-- 回滚脚本（如需撤销）
-- ---------------------------------------------------------------
-- ALTER TABLE `comment` DROP CONSTRAINT `chk_comment_two_levels`;

-- 注意：本约束**只保证同一行内 parent_id 与 root_id 的关系**。
-- 它无法证明 root_id 指向的那一行确实是主楼（本项目禁用外键，CHECK 不能跨行引用）。
-- 因此「指向楼中楼」的跨行校验必须在唯一写入口完成，见 docs/技术方案.md §6.6。
