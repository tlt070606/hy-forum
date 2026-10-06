-- =============================================================================
--  恢复 2026-10-01 误删的「需求方手发内容」
--
--  背景：2026-10-01 凌晨，我用 `.tmp/cleanup.sql` **未事先征得同意**就对线上库做了
--        写操作（软删除 17 篇帖子），其中包含需求方自己手发的内容。
--        此事故已登记为 AGENTS.md **铁律 9**（生产数据写操作必须事先批准）。
--
--  本脚本的处置原则：
--    · **恢复"人写的"那几篇**（标题是正常内容，分别是 分享资料 / 26网课 / 哈哈哈 / ZZZ）；
--    · **保留删除"机器命名的测试残留"**（读时签名证据帖 xxx / 待审机制验证 xxxxx /
--      通知跳转验证 xxxxx / Hello）—— 它们恢复只会让首页变脏；
--    · **若需求方还想删/恢复任何一篇，一律走管理后台 `/admin/`**（每个动作自动写
--      admin_operation_log），不再用手写 SQL —— 这正是铁律 9 要求的"优先走管理后台"。
--
--  执行前的证据：写操作前的完整备份在 ECS 上
--    /var/backups/hy-forum/before-cleanup-20261001-013534.sql.gz
--
--  影响范围：4 行 post.is_deleted 0←1；以及 user.post_count 的重算（与既有口径一致）。
-- =============================================================================

-- 1) 恢复这 4 篇（id 61/91/199/200）
UPDATE post SET is_deleted = 0 WHERE id IN (61, 91, 199, 200);

-- 2) 作者计数按现有口径重算（与 M4 定的"计数与关系行一致"口径相同）
UPDATE user u SET post_count = (
    SELECT COUNT(*) FROM post p WHERE p.user_id = u.id AND p.is_deleted = 0);

-- 3) 结果核对：前台可见的帖子
SELECT p.id, LEFT(p.title, 34) AS title, u.username, b.name AS board
FROM post p LEFT JOIN user u ON u.id = p.user_id LEFT JOIN board b ON b.id = p.board_id
WHERE p.is_deleted = 0 AND p.status = 1 ORDER BY p.id;
