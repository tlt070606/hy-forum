-- =============================================================
-- 增量迁移脚本  20260914_add_post_collect_and_admin_operation_log.sql
-- 对应变更：docs/db/README.md「变更登记」2026-09-14 两条
-- 对应基线：docs/db/schema.sql 表 15、表 16
--
-- 适用场景：
--   * 全新环境 → 直接执行 docs/db/schema.sql（已含这两张表），**不要**执行本脚本
--   * 已按 v1.3 建过库的环境 → 执行本脚本
-- 本脚本中两个 CREATE TABLE 的内容与 schema.sql 表 15/16 **逐字一致**，
-- 修改时必须同步改两处，否则视为缺陷（见 docs/db/README.md 变更冻结流程）。
-- =============================================================

USE hy_forum;

-- ---------------------------------------------------------------
-- 15. 帖子收藏（v1.4 新增）
--     收藏为「帖子↔用户」多对多关系，必须独立成表：
--     post.collect_count 只是列表页展示用的冗余计数，不能代替关系数据，
--     否则 GET /api/user/collections（我的收藏列表）无数据来源。
--     结构与 post_like 保持一致：唯一索引保证幂等，同事务维护 post.collect_count。
-- ---------------------------------------------------------------
CREATE TABLE `post_collect` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `post_id`    BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 幂等红线：同一用户对同一帖子只能有一条有效收藏记录（对应技术方案 §8.1 的 DuplicateKey 语义）
  UNIQUE KEY `uk_post_user` (`post_id`, `user_id`),
  -- 个人主页「我的收藏」按时间倒序分页
  KEY `idx_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子收藏';

-- ---------------------------------------------------------------
-- 16. 管理操作留痕（v1.4 新增，合规义务 C9）
--     为什么必须独立成表：C9 要求「管理员、时间、对象、动作、理由」保留 ≥ 6 个月，
--     而 post/comment 只有业务状态字段、没有审核人信息，
--     且应用文件日志只保留 30 天（见技术方案 §11）——用文件日志承担留痕义务必然违规。
--     因此留痕走结构化存储：只增不改不删，任何写操作都必须先落本表再返回。
--     action 取值示例：POST_STATUS / POST_TOP / POST_ESSENCE / POST_DELETE /
--                     COMMENT_DELETE / USER_BAN / USER_UNBAN / CONFIG_UPDATE /
--                     INVITE_CODE_CREATE / INVITE_CODE_DISABLE / SENSITIVE_WORD_ADD /
--                     SENSITIVE_WORD_DELETE / BOARD_ADD / BOARD_UPDATE / BOARD_DELETE
-- ---------------------------------------------------------------
CREATE TABLE `admin_operation_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `admin_id`    BIGINT UNSIGNED NOT NULL COMMENT '操作人，对应 admin.id（管理员与前台用户隔离，见技术方案 §9）',
  `action`      VARCHAR(40)     NOT NULL COMMENT '动作编码，见上方注释枚举',
  `target_type` TINYINT         NULL COMMENT '1帖子 2评论 3用户 4系统配置 5邀请码 6敏感词 7版块；无具体对象时为空',
  `target_id`   BIGINT UNSIGNED NULL COMMENT '对象主键；无具体对象时为空',
  `reason`      VARCHAR(200)    NULL COMMENT '处置理由；屏蔽/封禁/删除类动作必填（合规要求可追溯处置依据）',
  `detail`      VARCHAR(500)    NULL COMMENT '变更摘要，如 status:1->2、register_mode:open->invite',
  `ip`          VARCHAR(45)     NULL COMMENT '操作来源 IP，45 字符以兼容 IPv6',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 「某个管理员做过什么」：后台按操作人追溯
  KEY `idx_admin`  (`admin_id`, `created_at`),
  -- 「某个对象被谁处置过」：管理员查看帖子/用户的处置历史
  KEY `idx_target` (`target_type`, `target_id`, `created_at`)
  -- 注意：本表无 is_deleted。留痕记录不可删除，清理只能按 created_at 滚动淘汰 ≥ 6 个月之前的数据
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理操作留痕（合规 C9，保留 ≥ 6 个月）';
