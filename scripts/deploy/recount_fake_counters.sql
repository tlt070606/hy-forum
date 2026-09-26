-- =============================================================================
--  重算计数：清掉 E2E 测试刷出来的假数字
--
--  背景：导库时我把 `view_count`/`like_count` 等**计数字段**原样带了过来，
--        而它们大多是历次 E2E 测试刷出来的（例如 post 173 "Hello" 显示 120 次浏览）。
--        用户第一眼就会看到这些假数字 —— 这是导库时的疏漏。
--
--  口径：**计数一律以真实关系行为准**（这也是 M4 定的口径：计数与关系行同事务）。
--        · like_count    = post_like 的真实行数
--        · collect_count = post_collect 的真实行数
--        · comment_count = comment 的真实行数
--        · view_count    = 0（浏览量走 Redis，历史值全是测试数据，归零后从真实访问重新累积）
--        · 用户侧 post_count / fans_count / follow_count 同理重算
-- =============================================================================

UPDATE post p SET
  like_count    = (SELECT COUNT(*) FROM post_like    l WHERE l.post_id = p.id),
  collect_count = (SELECT COUNT(*) FROM post_collect c WHERE c.post_id = p.id);

UPDATE post p SET
  comment_count = (SELECT COUNT(*) FROM comment cm WHERE cm.post_id = p.id),
  view_count    = 0;

UPDATE user u SET
  post_count   = (SELECT COUNT(*) FROM post  p WHERE p.user_id = u.id AND p.is_deleted = 0),
  fans_count   = (SELECT COUNT(*) FROM follow f WHERE f.followed_id = u.id),
  follow_count = (SELECT COUNT(*) FROM follow f WHERE f.user_id     = u.id);

SELECT id, LEFT(title, 24) AS title, view_count, like_count, collect_count, comment_count
FROM post WHERE is_deleted = 0 ORDER BY id DESC LIMIT 8;

SELECT id, username, post_count, fans_count, follow_count FROM user ORDER BY id;
