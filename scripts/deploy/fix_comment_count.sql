-- =============================================================================
--  修复 comment_count 口径（单表定点修复版；全量重算用 recount_fake_counters.sql）
--
--  口径（2026-10-01 与代码统一）：comment_count == 该帖**可见**评论行数
--      （is_deleted = 0 AND status = 1；待审/已屏蔽不进计数 —— 需求方裁定，
--      见 InteractionService / CommentService / CommentAuditService 的口径注释）。
--
--  ⚠️ 本文件的中文注释曾是乱码（编码事故）：写入时用了非 UTF-8 编码，
--     读出来全是问号。教训：仓库统一 UTF-8（AGENTS.md / .gitattributes 有约定），
--     PowerShell 重定向写文件时注意 -Encoding utf8。
-- =============================================================================

UPDATE post p SET
  comment_count = (
      SELECT COUNT(*) FROM comment cm
      WHERE cm.post_id = p.id AND cm.is_deleted = 0 AND cm.status = 1);
SELECT id, LEFT(title,22) AS title, view_count, like_count, collect_count, comment_count
FROM post WHERE is_deleted = 0 ORDER BY id DESC LIMIT 8;
