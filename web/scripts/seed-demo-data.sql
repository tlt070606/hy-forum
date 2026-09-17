-- ============================================================================
-- 演示数据（种子）—— **只为让首页/详情页看起来像成品**，不是业务数据
-- ============================================================================
-- ⚠️ 本文件**不建表、不改表结构、不动任何契约**。它只往开发库 `hy_forum`
--    的 `user` / `post` 两张表里插一批演示行。表结构仍然只由 `docs/db/schema.sql` 说了算。
--
-- 为什么要灌库而不是在前端写死假帖子：
--   帖子流走的是**真接口**（`GET /api/posts` / `GET /api/posts/{id}`），所以帖子必须真的在库里
--   —— 这样页面每一张卡片、每一个字段都是"真的从后端读出来的"，契约也因此被真的跑过一遍。
--   纯前端写死的假帖子会让界面看起来是好的、而契约根本没被碰过。
--
-- 幂等性：
--   脚本开头会先删掉上一批（按 `username` 前缀 `demo_` 识别作者），再重新插入。
--   因此**可以重复执行**，不会累积。它不碰 `e2e_*` 用户、不碰真实数据。
--
-- ⚠️ 三个刻意的设计：
--   1. 演示账号的 `password_hash` 写成 `demo-account-no-login` ——
--      **这不是合法的 BCrypt 哈希**，因此这些账号**永远登不进去**（登录返回 1002）。
--      它们存在的唯一目的是当作者。这样既不用复制任何人的密码哈希，也不可能被误用登录。
--   2. **所有计数一律为 0**（`view_count` / `like_count` / `comment_count` / `collect_count`）。
--      2026-09-17 修正：这些数字最初被我写成了编造的漂亮值（12860 次浏览、642 赞…），
--      而**点赞/评论/收藏接口根本不存在**（不在契约的 14 个路径里，属 M4）——
--      于是详情页会出现"评论 118"而下面一条评论也没有的**自相矛盾**。
--      浏览数同样是编的，所以一并清零。**清零后页面上的每个数字都是真的**：
--      浏览数会随真实访问由后端（Redis）累加，其余保持 0 直到 M4 接口到位。
--   3. `created_at` 往前错开 3 小时~6 天，让列表里的相对时间有层次，
--      而不是十条全是"刚刚"。
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 0. 清理上一批（作者用户名前缀 demo_）
-- ---------------------------------------------------------------------------
DELETE FROM `post` WHERE `user_id` IN (SELECT `id` FROM `user` WHERE LEFT(`username`, 5) = 'demo_');
DELETE FROM `user` WHERE LEFT(`username`, 5) = 'demo_';

-- ---------------------------------------------------------------------------
-- 1. 演示作者（6 个）
--    password_hash 见文件头说明：非法哈希 = 不可登录
-- ---------------------------------------------------------------------------
INSERT INTO `user` (`username`, `password_hash`, `nickname`, `bio`, `avatar_url`, `gender`, `post_count`, `follow_count`, `fans_count`, `like_received_count`, `status`, `is_deleted`) VALUES
('demo_azhe',    'demo-account-no-login', '阿哲聊科技',     '每天一个 AI 实用技巧',       NULL, 1, 0, 0, 0, 0, 1, 0),
('demo_laowang', 'demo-account-no-login', '厨房里的老王',   '家常菜十年，专治不会做饭',   NULL, 1, 0, 0, 0, 0, 1, 0),
('demo_xiaoman', 'demo-account-no-login', '程序媛小满',     '后端开发 / 分享搬砖日常',    NULL, 2, 0, 0, 0, 0, 1, 0),
('demo_shanye',  'demo-account-no-login', '山野笔记',       '徒步与露营装备实测',         NULL, 1, 0, 0, 0, 0, 1, 0),
('demo_achai',   'demo-account-no-login', '一只阿柴',       '养宠八年，踩过的坑都在这',   NULL, 2, 0, 0, 0, 0, 1, 0),
('demo_xiaolin', 'demo-account-no-login', '小林的生活实验', '认真生活，不定期更新',       NULL, 1, 0, 0, 0, 0, 1, 0);

-- ---------------------------------------------------------------------------
-- 2. 演示帖子（10 条）
--    board_id 取自 seed 的 7 个版块（docs/技术方案.md §4.3）：
--      1 公告区(仅管理员) / 2 资源分享(资源版) / 3 综合讨论 / 4 求助问答
--      5 技术学习 / 6 灌水闲聊 / 7 站务反馈
--    ⚠️ 刻意**不使用** board 1（公告区仅管理员可发帖），否则演示数据本身就违反业务规则。
--    ⚠️ 计数列一律 0，理由见文件头第 2 条。
-- ---------------------------------------------------------------------------
INSERT INTO `post`
  (`board_id`, `user_id`, `title`, `content`, `cover_url`, `image_count`, `disk_type`, `disk_url`, `disk_code`,
   `view_count`, `like_count`, `comment_count`, `collect_count`, `is_top`, `is_essence`, `status`, `created_at`, `is_deleted`)
VALUES
-- 技术学习：AI 工具实测
(5, (SELECT `id` FROM `user` WHERE `username`='demo_azhe'),
 'AI 工具实测：这 5 个真的能省下每天一小时',
 '连着用了三周，把手上重复的活都试了一遍。写周报、整理会议纪要、扒文档、做表格、查报错——这 5 件事是真的能交出去的，其余大多还是玩具。\n\n最有用的一条经验：别指望它一次做对，把它当成一个很快但需要复核的实习生。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 1, 1, NOW() - INTERVAL 3 HOUR, 0),

-- 综合讨论：不吹不黑
(3, (SELECT `id` FROM `user` WHERE `username`='demo_azhe'),
 '用了半年 AI 写代码，说点不吹不黑的',
 '提效确实有，但不在"写"上，在"读"上：读陌生代码库、读报错、读文档，这三件事省下的时间最多。\n\n真正踩坑的地方是它**过于自信**——会编出一个看起来完全合理的 API，而且编得很像。所以我的规矩是：不认识的接口一律去官方文档核一遍。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 1, 1, NOW() - INTERVAL 8 HOUR, 0),

-- 资源分享（资源版块 → 带网盘字段）
(2, (SELECT `id` FROM `user` WHERE `username`='demo_xiaoman'),
 '整理了一份 Java 后端转 AI 应用的资料清单',
 '按顺序看就行，不用全看完。前三份是必读，后面的按需查。\n\n链接里我已经把提取码填好了，直接复制整段就行。',
 NULL, 0, 1, 'https://pan.baidu.com/s/1HyForumDemo', 'hy28', 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 1 DAY, 0),

-- 灌水闲聊：带饭
(6, (SELECT `id` FROM `user` WHERE `username`='demo_laowang'),
 '带饭上班第 30 天，附一周菜谱',
 '省下来的钱其实不是重点，重点是中午不用纠结吃什么了。\n\n一周菜谱：周一番茄牛腩 / 周二香菇滑鸡 / 周三土豆烧肉 / 周四咖喱鸡 / 周五随便炒。都是周日一次做完分装冷冻的。\n\n唯一的问题是公司微波炉要排队，11:30 就得去。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 1, 1, NOW() - INTERVAL 1 DAY - INTERVAL 5 HOUR, 0),

-- 综合讨论：反内耗
(3, (SELECT `id` FROM `user` WHERE `username`='demo_xiaolin'),
 '把通勤的一小时还给自己之后',
 '以前通勤全程刷短视频，到公司就已经累了。现在改成听播客或者干脆什么都不听。\n\n说不上效率提升多少，但**下班时没那么空**了。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 2 DAY, 0),

-- 灌水闲聊：手机摄影
(6, (SELECT `id` FROM `user` WHERE `username`='demo_shanye'),
 '手机摄影：把日常拍出杂志感的 6 个习惯',
 '一、先找光，再找景。二、镜头擦干净（真的有用）。三、宁可欠曝不要过曝。四、留白比塞满难。五、同一场景连拍 5 张选一张。六、别急着加滤镜。\n\n第三条是我觉得最容易被忽略的：手机自动测光偏爱把画面提亮，拍出来就"平"。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 2 DAY - INTERVAL 9 HOUR, 0),

-- 求助问答：真问题
(4, (SELECT `id` FROM `user` WHERE `username`='demo_xiaolin'),
 '小程序端 request 域名备案到底怎么走？',
 '本地用开发者工具的"不校验合法域名"能跑通，但真机就不行了。想问问有没有人走完过整个备案流程，大概要多久、卡在哪一步。\n\n（个人主体是不是根本没戏？）',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 3 DAY, 0),

-- 综合讨论：读书
(3, (SELECT `id` FROM `user` WHERE `username`='demo_achai'),
 '九月读书打卡：读完《置身事内》',
 '这本书最打动我的不是结论，而是它把"地方政府为什么要这么做"讲成了一件有约束条件下不得不做的事。\n\n以前看新闻只会觉得荒唐，现在能看出一点结构性的无奈。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 4 DAY, 0),

-- 灌水闲聊：养宠
(6, (SELECT `id` FROM `user` WHERE `username`='demo_achai'),
 '养猫两年，最后悔买的 5 件东西',
 '猫窝（它只睡纸箱）、自动逗猫棒（三天就腻）、猫用饮水机（清洗太麻烦）、第二只猫爬架（一个够用）、以及一切"看起来高级"的玩具。\n\n最值的反而是最便宜的：逗猫棒、纸箱、和一个能晒太阳的窗台。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 5 DAY, 0),

-- 技术学习：路线图
(5, (SELECT `id` FROM `user` WHERE `username`='demo_xiaoman'),
 '后端转 AI 应用开发，我的三个月路线图',
 '第一个月补 Python 和数据处理，第二个月做两个能跑通的小项目（RAG 问答、批量文档摘要），第三个月才去看模型原理。\n\n顺序很关键：**先能跑起来，再理解为什么**。反过来很容易卡在数学上然后放弃。',
 NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 1, NOW() - INTERVAL 6 DAY, 0);

-- ---------------------------------------------------------------------------
-- 3. 把冗余计数补齐
--    ⚠️ 只补 `post_count`（它等于真实行数，不是编造的数字）。
--       **不要**在这里给 view/like/comment/collect 填值 —— 理由见文件头第 2 条。
-- ---------------------------------------------------------------------------
UPDATE `user` u
SET u.`post_count` = (SELECT COUNT(*) FROM `post` p WHERE p.`user_id` = u.`id` AND p.`is_deleted` = 0)
WHERE LEFT(u.`username`, 5) = 'demo_';

UPDATE `board` b
SET b.`post_count` = (SELECT COUNT(*) FROM `post` p WHERE p.`board_id` = b.`id` AND p.`is_deleted` = 0);

-- ---------------------------------------------------------------------------
-- 4. 修正 `updated_at`：**必须等于 `created_at`**
--    ⚠️ 上面的 INSERT 只显式给了 `created_at`，而 `updated_at` 会走列默认值
--       `CURRENT_TIMESTAMP`（= 灌库那一刻）。于是"一篇从没编辑过的帖子"会出现
--       `updated_at` 比 `created_at` 晚好几天。
--       后果是**看得见的**：前端用 `updatedAt - createdAt > 60s` 判断"编辑过"，
--       于是详情页会给每条演示帖都挂上一句「编辑于 1 分钟前」——
--       一个纯粹的种子数据缺陷，被渲染成了业务事实。
--       （2026-09-17 截图自检时抓到；真实帖经 `POST /api/posts` 创建时两列同值，不会这样。）
-- ---------------------------------------------------------------------------
UPDATE `post` p JOIN `user` u ON u.`id` = p.`user_id`
SET p.`updated_at` = p.`created_at`
WHERE LEFT(u.`username`, 5) = 'demo_';

-- 校验输出（跑完能一眼看到结果）
SELECT 'demo users' AS what, COUNT(*) AS n FROM `user` WHERE LEFT(`username`,5)='demo_'
UNION ALL SELECT 'demo posts', COUNT(*) FROM `post` p JOIN `user` u ON u.`id`=p.`user_id` WHERE LEFT(u.`username`,5)='demo_'
UNION ALL SELECT 'visible posts', COUNT(*) FROM `post` WHERE `is_deleted`=0
UNION ALL SELECT 'sum of fake counts', (SELECT IFNULL(SUM(view_count+like_count+comment_count+collect_count),0) FROM `post`);
