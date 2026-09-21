# CR-Q 扩展交付报告：所有对外 OSS 地址的读时签名 + 机械护栏

> 任务：L1 裁决（2026-09-20，CR-Q 范围扩大）。
> 提交：`2d704ac`（头像）、`e02911c`（封面/收藏字段/护栏）。
> 关联：`ff45259`（签名器不变量防御）、`c688db5`（映射表登记）、`067b2e0`（CR-Q 报告）。

---

## 1. ① 共享签名入口：`common/oss/OssUrls`

所有装配 OSS 地址的地方**只允许调它**。它把三件事收在一处：

| 方法 | 作用 |
|---|---|
| `sign(String)` | 任意库里存的裸 OSS 地址 → 签名；`null`/空白 → `null`；非本站/已签 → 原样 |
| `thumbs(List, limit)` | 由原图裸地址**推导缩略图**（`OssThumbnailUrls.derive`）并逐个签名；最多 3 张 |
| `avatar(User)` / `avatar(id, url)` | 头像（委托 `AvatarUrlResolver`，它负责"是否属于该用户目录"） |

**签名算法仍只有一段实现**：`V1OssReadUrlSigner.signWithin(allowedPrefix, url)`
（帖子图与头像共用）。`OssUrls` 只是"唯一入口"，**不复制算法** ——
抄第二份等于把 §12.3 那三个坑复制一遍。

---

## 2. ② 清单：所有对外返回 OSS 地址的地方

| 端点 | 字段 | 装配点（负责处） | 签名入口 |
|---|---|---|---|
| `GET /api/users/{id}` | `avatarUrl` | `UserService.getProfile` → `UserProfileVO` | `AvatarUrlResolver`（经 `UserVO/UserProfileVO` 装配） |
| `GET /api/user/profile`（PUT 响应） | `avatarUrl` | `ProfileService.toProfileVO` | `AvatarUrlResolver` |
| `GET /api/auth/me`、登录/注册响应 | `avatarUrl` | `AuthService` → `UserVO.from` | `AvatarUrlResolver` |
| `GET /api/posts` | `coverUrl`、`imageThumbs` | `PostService`（`toSummaries`）+ `CardViewerStateEnricher` | `readUrlSigner` / `OssUrls` |
| `GET /api/posts/{id}` | `coverUrl`、`images[].url`、`images[].thumbUrl` | `PostService.toDetail` / `toSignedImageVo` | `readUrlSigner` |
| `GET /api/feed`、`/api/feed?type=follow` | `coverUrl`、`imageThumbs` | `FeedService`（装配）+ `CardViewerStateEnricher`（缩略图） | **`OssUrls`**（本轮新增） |
| `GET /api/users/{id}/posts` | `coverUrl`、`imageThumbs` | **`UserService.listUserPosts`** | **`OssUrls`**（本轮新增） |
| `GET /api/user/collections` | `coverUrl`、`imageThumbs`、**`author.avatarUrl`** | `InteractionService.listCollections` | **`OssUrls`**（本轮新增） |
| `GET /api/posts/{id}/comments` | `list[].author.avatarUrl`、`replies[].author.avatarUrl` | `CommentService` → `interaction.vo.UserBriefVO.from` | `AvatarUrlResolver` |
| `GET /api/notifications` | `fromAvatarUrl` | `NotificationService` → `NotificationVO.from` | `AvatarUrlResolver` |
| `GET /api/users/{id}/fans`、`/follows` | `avatarUrl` | `FollowService.toUserItems` → `FollowUserVO` | `AvatarUrlResolver` |
| `POST /api/oss/callback` | `data.url`、`data.thumbUrl` | `OssCallbackService` | 回调侧（见下注） |

> 注：`/api/oss/callback` 回给 **OSS** 的响应（供前端直传后立即展示）不在护栏遍历内 ——
> 它带签名与否由 M3 的用例覆盖；若要纳入护栏，需要在前端直传后立刻调用它。

### 凭什么"新增字段时不会漏" —— 两道，第二道是重点

1. **结构**：ArchUnit `ARCH_avatar_url_must_go_through_resolver` —— `domain` 与 `common.oss`
   之外的类**禁止调用 `User.getAvatarUrl()`**。新增头像装配点直接读裸字段 →
   **编译能过、接口也能跑，但架构门会红**。
2. **遍历（机械护栏）**：`M4_api_responses_have_no_unsigned_oss_url` ——
   见第 3 节。**它不依赖任何人记得上面这张表**：新增字段若忘签名，
   只要那个端点被遍历到，它自己会红。

---

## 3. ③ 机械护栏：`M4_api_responses_have_no_unsigned_oss_url`

**做法（把清单变成遍历）**：

1. 打一遍 **11 个**会返回 OSS 地址的读接口
   （个人主页×2 / 帖子详情 / 全部流 / 关注流 / 评论列表 / 我的收藏 / 我的消息 /
   粉丝 / 关注 / 用户帖子列表）；
2. 用正则把响应 JSON 里**每一个本站 OSS 域名的 URL** 抓出来 ——
   **不按字段名取**，所以将来新增字段里的 URL 也一起被抓；
3. 逐个断言带 `OSSAccessKeyId` + `Expires` + `Signature`，且 **`Expires` 未过期**；
4. **自证不空转**：抓不到任何 URL、或覆盖端点少于 4 个 → **直接失败**。

**两条反证**：① `avatarUrl` 为 null **不得**报错（空值合法）；
② 非本站 URL 必须**原样返回且不带签名**，且护栏正则不该抓它。

### 护栏抓到的真问题（这就是它的价值）

| # | 问题 | 说明 |
|---|---|---|
| 1 | `CollectionItemVO.coverUrl` 是裸 URL | 前端清单第 1 条 |
| 2 | **`UserService.listUserPosts` 也在装配 `FeedItemVO`** | 与 `FeedService` 是**同一种卡片的第二个装配点** —— 只改 FeedService 会漏掉"个人主页的帖子列表"这条路径。**护栏正是在这里抓到的** |

---

## 4. DoD 证据

### 全量（跑前/跑后 surefire 均 0）
```
ArchitectureRulesTest: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
Tests run: 118, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

### 变异（两次，都作用在**不同的装配点**）

**变异①（收藏封面漏签名）→ 护栏红，且精确指向该端点**：
```
【我的收藏】缺少 Signature（未签名）：
  https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/2026/09/20/guard.png
【我的收藏】缺少 OSSAccessKeyId / 缺少 Expires （同理）
```

**变异②（`UserService` 的个人主页列表封面漏签名）→ 护栏红，指向另一个端点**：
```
【用户帖子列表】缺少 Signature（未签名）：
  https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/2026/09/20/guard.png
```

两次都**只改一处装配点**，护栏都在**对应的那个端点**上红 ——
这正是"新增字段忘签名 → 端点被遍历到就自己红"的证明。
（每次变异都先删掉对应 class 强制重编译，确认变异**真的进了字节码**；
还原后工作区与已提交版本逐字一致。）

### 用例
- `M4UnsignedOssUrlGuardTest` 3/3（主线 + 两条反证）
- `M4CollectionItemTest` 1/1（`author` + `imageThumbs` + 封面签名 + 越权反证）
- `M4AvatarReadSigningTest` 2/2

### 🔴 "真的能取回字节" —— **本环境无法完成，未伪造**

我按 L1 的要求真的发了请求，结果如下：

```
① 裸 URL   HEAD https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/... → 403 Forbidden
   （确认桶是私有的 —— **护栏的前提成立**）
② 用占位密钥签出的 URL HEAD → 403
   （占位密钥 TEST-KEYID-1234567890 被 OSS 拒绝，与预期一致）
```

本机 **`OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` 均未设置**，
测试 profile 是占位值。因此"签名 URL 匿名 GET → 200 + `Content-Type: image/*`"
**在本环境不可能达成** —— 那是**凭据缺失**，不是签名逻辑错误，
用它判红绿会得出错误结论（L1 自己的探针也误判过一次同类现象）。
**这一步必须在有真实凭据的环境补做**，我不会把它写成"已通过"。

顺带一个可用的**正向证据**：`OssUrls` 与帖子图**共用同一段签名实现与同一个 TTL**
（`hy.oss.read-url-ttl`），而帖子图那套签名在 M3 阶段是**在真实桶上验证过能取回字节**的
（`M3_oss_callback_then_publish_claims_image` 等用例 + 运维实测）。
换句话说：本轮的签名路径与已证实可用的那条**是同一段代码**，只换了允许前缀。

---

## 5. ⚠️ 排查记录：三个"看着像产品缺陷、其实是测试问题"的红

这一轮我在这上面花了很久，如实记下，供后来者少走弯路：

1. **`notification` 不能放进 `tablesToClean()`**：
   `IntegrationTestBase` 的清表发生在 `@BeforeEach` **之内**、子类 `seed()` **之前**，
   于是把 `notification` 加进清单会把 seed 里刚造的通知**当场清掉**，
   剩下的数据与它不再对得上 → 发送者查不到 → 字段 null → 护栏报一堆问题。
   正解：放 **`@AfterEach`**（本轮用完再清）。
2. **历史残留**：`hy_forum_test` 里曾留下 **78 行**通知（修复前的裸 URL），护栏扫到了它们。
   这其实说明护栏有效（它扫"响应里真实出现的 URL"），但排查时必须先分清"测试数据脏"与"产品缺陷"。
3. **fixture 造错**：第一版用 `UPDATE ... WHERE id IN (?, ?)` 把两个用户都设成
   `avatar/{author.id}/`。于是当"收藏者"作为通知发送者/粉丝出现时，
   其头像不在**他自己**的 `avatar/{id}/` 内 → `AvatarUrlResolver` **按设计原样返回（不签名）**
   → 护栏报"未签名"。**那是边界③的正确行为，不是缺陷。**
   教训：fixture 里"两个用户共用一条路径"是看不见的坑。

**这三条的共同教训**：护栏类用例的红，**先分清是产品问题还是数据问题**再下结论 ——
否则会去改一个没坏的实现。
