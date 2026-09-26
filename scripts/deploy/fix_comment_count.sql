-- ?? comment_count ??????**????????**????????????????
UPDATE post p SET
  comment_count = (
      SELECT COUNT(*) FROM comment cm
      WHERE cm.post_id = p.id AND cm.is_deleted = 0 AND cm.status = 1);
SELECT id, LEFT(title,22) AS title, view_count, like_count, collect_count, comment_count
FROM post WHERE is_deleted = 0 ORDER BY id DESC LIMIT 8;
