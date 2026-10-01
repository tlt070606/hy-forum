-- =============================================================================
--  重算计数：清掉 E2E 测试刷出来的假数字（兜底校准脚本）
--
--  背景：导库时我把 `view_count`/`like_count` 等**计数字段**原样带了过来，
--        而它们大多是历次 E2E 测试刷出来的（例如 post 173 "Hello" 显示 120 次浏览）。
--        用户第一眼就会看到这些假数字 —— 这是导库时的疏漏。
--
--  口径（2026-10-01 修正：与代码口径严格一致，注释互为指认）：
--        · like_count    = post_like 的真实行数（纯关系表，无状态）
--        · collect_count = post_collect 的真实行数（同上）
--        · comment_count = comment 中**可见**行数（is_deleted = 0 AND status = 1；
--          待审/已屏蔽不进计数 —— 2026-10-01 需求方裁定，见
--          InteractionService / CommentService / CommentAuditService 的口径注释）
--        · view_count    = 0（浏览量走 Redis，历史值全是测试数据，归零后从真实访问重新累积）
--        · 用户侧 post_count / fans_count / follow_count 同理重算
--
--  ⚠️ 2026-10-01 修正记录：本脚本曾有两处错误（上线前写的，从未真跑通过）——
--        ① `follow.followed_id` 列不存在（schema 里是 `target_user_id`，
--          docs/db/schema.sql 的 follow 表），一执行就 Unknown column 报错；
--        ② comment_count 的口径与代码不一致（这里把待审/屏蔽也算进去了）。
--        教训：**兜底脚本不验证等于没有兜底** —— 修完应在测试库实跑一遍再入库。
-- =============================================================================

UPDATE post p SET
  like_count    = (SELECT COUNT(*) FROM post_like    l WHERE l.post_id = p.id),
  collect_count = (SELECT COUNT(*) FROM post_collect c WHERE c.post_id = p.id);

UPDATE post p SET
  comment_count = (SELECT COUNT(*) FROM comment cm
                   WHERE cm.post_id = p.id AND cm.is_deleted = 0 AND cm.status = 1),
  view_count    = 0;

UPDATE user u SET
  post_count   = (SELECT COUNT(*) FROM post  p WHERE p.user_id = u.id AND p.is_deleted = 0),
  fans_count   = (SELECT COUNT(*) FROM follow f WHERE f.target_user_id = u.id),
  follow_count = (SELECT COUNT(*) FROM follow f WHERE f.user_id       = u.id);

SELECT id, LEFT(title, 24) AS title, view_count, like_count, collect_count, comment_count
FROM post WHERE is_deleted = 0 ORDER BY id DESC LIMIT 8;

SELECT id, username, post_count, fans_count, follow_count FROM user ORDER BY id;
