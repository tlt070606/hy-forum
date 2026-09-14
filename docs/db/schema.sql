-- =============================================================
-- Hy论坛 建表脚本  schema.sql
-- MySQL 8.0 / InnoDB / utf8mb4 / Asia/Shanghai
-- 共 16 张表
-- 规范见 docs/db/README.md；变更必须先改本文件再改库
-- 修订：v1.4（2026-09-14）新增 post_collect（收藏）、admin_operation_log（审核留痕）
--       增量脚本见 migrations/20260914_add_post_collect_and_admin_operation_log.sql
--       v1.7（2026-09-14）comment 表新增 CHECK 约束 chk_comment_two_levels（两层结构不变量，P1-3 定案）
--       增量脚本见 migrations/20260914_add_comment_two_level_check.sql
-- =============================================================

CREATE DATABASE IF NOT EXISTS hy_forum
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE hy_forum;

-- ---------------------------------------------------------------
-- 1. 用户
-- ---------------------------------------------------------------
CREATE TABLE `user` (
  `id`                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  `username`            VARCHAR(20)      NOT NULL COMMENT '登录名',
  `password_hash`       VARCHAR(100)     NOT NULL COMMENT 'BCrypt，禁止明文',
  `nickname`            VARCHAR(20)      NOT NULL COMMENT '昵称',
  `avatar_url`          VARCHAR(500)     NULL,
  `email`               VARCHAR(100)     NULL COMMENT '可选，用于找回密码',
  `bio`                 VARCHAR(200)     NULL COMMENT '个性签名',
  `gender`              TINYINT          NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女',
  `post_count`          INT UNSIGNED     NOT NULL DEFAULT 0,
  `follow_count`        INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '我关注的人数',
  `fans_count`          INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '关注我的人数',
  `like_received_count` INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '被点赞总数',
  `points`              INT              NOT NULL DEFAULT 0 COMMENT '【预留】积分',
  `level`               TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '【预留】等级',
  `status`              TINYINT          NOT NULL DEFAULT 1 COMMENT '1正常 0封禁',
  `last_login_at`       DATETIME         NULL,
  `created_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`          TINYINT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_status_created` (`status`, `is_deleted`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ---------------------------------------------------------------
-- 2. 版块
-- ---------------------------------------------------------------
CREATE TABLE `board` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(30)     NOT NULL,
  `slug`        VARCHAR(30)     NOT NULL,
  `description` VARCHAR(200)    NULL,
  `icon_url`    VARCHAR(500)    NULL,
  `sort`        INT             NOT NULL DEFAULT 0 COMMENT '升序',
  `is_resource` TINYINT         NOT NULL DEFAULT 0 COMMENT '1=资源版块，发帖显示网盘字段',
  `post_count`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`),
  KEY `idx_sort` (`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版块';

-- ---------------------------------------------------------------
-- 3. 帖子
-- ---------------------------------------------------------------
CREATE TABLE `post` (
  `id`            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  `board_id`      BIGINT UNSIGNED  NOT NULL,
  `user_id`       BIGINT UNSIGNED  NOT NULL,
  `title`         VARCHAR(100)     NOT NULL,
  `content`       TEXT             NULL,
  `cover_url`     VARCHAR(500)     NULL COMMENT '列表页封面，取首图缩略图',
  `image_count`   TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `disk_type`     TINYINT          NULL COMMENT '1百度 2阿里 3夸克 4天翼 5迅雷 6其他；仅资源版',
  `disk_url`      VARCHAR(500)     NULL COMMENT '网盘分享链接；仅资源版',
  `disk_code`     VARCHAR(20)      NULL COMMENT '提取码，可空；仅资源版',
  `view_count`    INT UNSIGNED     NOT NULL DEFAULT 0,
  `like_count`    INT UNSIGNED     NOT NULL DEFAULT 0,
  `comment_count` INT UNSIGNED     NOT NULL DEFAULT 0,
  `collect_count` INT UNSIGNED     NOT NULL DEFAULT 0,
  `report_count`  INT UNSIGNED     NOT NULL DEFAULT 0,
  `is_top`        TINYINT          NOT NULL DEFAULT 0 COMMENT '置顶',
  `is_essence`    TINYINT          NOT NULL DEFAULT 0 COMMENT '加精',
  `status`        TINYINT          NOT NULL DEFAULT 1 COMMENT '0待审核 1正常 2已屏蔽',
  `created_at`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`    TINYINT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_board_list` (`board_id`, `status`, `is_deleted`, `is_top` DESC, `created_at` DESC),
  KEY `idx_user_list`  (`user_id`, `status`, `is_deleted`, `created_at` DESC),
  KEY `idx_global_list`(`status`, `is_deleted`, `created_at` DESC),
  FULLTEXT KEY `ft_title_content` (`title`, `content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子';

-- ---------------------------------------------------------------
-- 4. 帖子图片
-- ---------------------------------------------------------------
CREATE TABLE `post_image` (
  `id`           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  `post_id`      BIGINT UNSIGNED  NOT NULL,
  `url`          VARCHAR(500)     NOT NULL,
  `thumb_url`    VARCHAR(500)     NULL COMMENT '九宫格缩略图',
  `width`        INT UNSIGNED     NOT NULL DEFAULT 0,
  `height`       INT UNSIGNED     NOT NULL DEFAULT 0,
  `sort`         TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `audit_status` TINYINT          NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2违规',
  `created_at`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_post` (`post_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子图片';

-- ---------------------------------------------------------------
-- 5. 评论（两层：主楼 + 楼中楼）
--    P1-3 定案（2026-09-14）：楼中楼的 parent_id 与 root_id **恒等于其所属主楼 id**（归并语义）。
--    由此三层结构在数据上不可能被表达；跨行引用校验见技术方案 §6.6。
-- ---------------------------------------------------------------
CREATE TABLE `comment` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `post_id`          BIGINT UNSIGNED NOT NULL,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `parent_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=主楼；否则为主楼评论id',
  `root_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '主楼为0；楼中楼存所属主楼id',
  `reply_to_user_id` BIGINT UNSIGNED NULL COMMENT '楼中楼回复的目标用户',
  `content`          VARCHAR(1000)   NOT NULL,
  `like_count`       INT UNSIGNED    NOT NULL DEFAULT 0,
  `reply_count`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '仅主楼维护',
  `status`           TINYINT         NOT NULL DEFAULT 1 COMMENT '0待审核 1正常 2已屏蔽',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_post_root` (`post_id`, `parent_id`, `status`, `is_deleted`, `created_at`),
  -- idx_root 必须保留：GET /api/comments/{rootId}/replies 只带 rootId，
  -- 若仅靠 parent_id 查询则必须同时提供 post_id 才能用上 idx_post_root
  KEY `idx_root`      (`root_id`, `created_at`),
  KEY `idx_user`      (`user_id`, `created_at`),
  -- 两层结构的机械不变量（P1-3 定案）：主楼 (0,0)；楼中楼 (主楼id, 主楼id)。
  -- 边界说明：CHECK 只能保证「同一行内两列的关系」，**不能**证明 root_id 指向的那一行确实是主楼
  --          （本项目禁用外键，无法跨行约束）→ 跨行校验必须在**唯一写入口**完成，见技术方案 §6.6。
  CONSTRAINT `chk_comment_two_levels`
    CHECK ((`parent_id` = 0 AND `root_id` = 0) OR (`parent_id` <> 0 AND `parent_id` = `root_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论（两层：主楼 + 楼中楼）';

-- ---------------------------------------------------------------
-- 6. 帖子点赞
-- ---------------------------------------------------------------
CREATE TABLE `post_like` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `post_id`    BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_user` (`post_id`, `user_id`),
  KEY `idx_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子点赞';

-- ---------------------------------------------------------------
-- 7. 评论点赞
-- ---------------------------------------------------------------
CREATE TABLE `comment_like` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `comment_id` BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_user` (`comment_id`, `user_id`),
  KEY `idx_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论点赞';

-- ---------------------------------------------------------------
-- 8. 关注关系
-- ---------------------------------------------------------------
CREATE TABLE `follow` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '关注者',
  `target_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注者',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow` (`user_id`, `target_user_id`),
  KEY `idx_target` (`target_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系';

-- ---------------------------------------------------------------
-- 9. 举报
-- ---------------------------------------------------------------
CREATE TABLE `report` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `target_type`     TINYINT         NOT NULL COMMENT '1帖子 2评论 3用户',
  `target_id`       BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '举报人',
  `reason_type`     TINYINT         NOT NULL COMMENT '1违法违规 2色情低俗 3广告垃圾 4侵权 5其他',
  `reason_detail`   VARCHAR(200)    NULL,
  `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0待处理 1已处理 2已驳回',
  `handler_id`      BIGINT UNSIGNED NULL,
  `handle_note`     VARCHAR(200)    NULL,
  `handled_at`      DATETIME        NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报';

-- ---------------------------------------------------------------
-- 10. 消息通知（本期实现，见技术方案 §6.10 与 M5）
-- ---------------------------------------------------------------
CREATE TABLE `notification` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '接收人',
  `type`         TINYINT         NOT NULL COMMENT '1点赞 2评论 3回复 4关注 5系统',
  `from_user_id` BIGINT UNSIGNED NULL,
  `target_type`  TINYINT         NULL COMMENT '1帖子 2评论',
  `target_id`    BIGINT UNSIGNED NULL,
  `content`      VARCHAR(200)    NULL,
  `is_read`      TINYINT         NOT NULL DEFAULT 0,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`, `is_read`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通知';

-- ---------------------------------------------------------------
-- 11. 管理员（与前台用户隔离）
-- ---------------------------------------------------------------
CREATE TABLE `admin` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username`      VARCHAR(30)     NOT NULL,
  `password_hash` VARCHAR(100)    NOT NULL,
  `nickname`      VARCHAR(30)     NOT NULL,
  `role`          VARCHAR(20)     NOT NULL DEFAULT 'ADMIN' COMMENT 'SUPER_ADMIN / ADMIN',
  `status`        TINYINT         NOT NULL DEFAULT 1,
  `last_login_at` DATETIME        NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员';

-- ---------------------------------------------------------------
-- 12. 敏感词
-- ---------------------------------------------------------------
CREATE TABLE `sensitive_word` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `word`       VARCHAR(50)     NOT NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词';

-- ---------------------------------------------------------------
-- 13. 系统配置（运行期可调参数，首项为注册模式）
-- ---------------------------------------------------------------
CREATE TABLE `sys_config` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key`   VARCHAR(50)     NOT NULL COMMENT '如 register_mode',
  `config_value` VARCHAR(500)    NOT NULL COMMENT '如 open / invite / closed',
  `remark`       VARCHAR(200)    NULL,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';

-- ---------------------------------------------------------------
-- 14. 邀请码（邀请制注册的降级方案）
-- ---------------------------------------------------------------
CREATE TABLE `invite_code` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`            VARCHAR(32)     NOT NULL,
  `creator_user_id` BIGINT UNSIGNED NULL,
  `used_by_user_id` BIGINT UNSIGNED NULL,
  `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用 2已失效',
  `expire_at`       DATETIME        NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `used_at`         DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请码';

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
