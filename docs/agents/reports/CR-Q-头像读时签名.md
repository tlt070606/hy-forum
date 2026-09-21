# CR-Q 交付报告：头像读时签名

> 任务：L1 裁决（2026-09-20）—— 修「帖子图能看、头像不能」，并用**结构**堵住"消费者没列全"这个重复了三次的形态。
> 关联提交：`2d704ac`（修复+用例）、`ff45259`（不变量防御）、`c688db5`（映射表登记）。

---

## 1. 缺陷与根因

桶是私有的（匿名 GET 对象 → 403）。帖子图在 §12 有**读时签名**所以能显示；
头像来得晚（§14），当时只做了**写侧前缀校验**，**读侧签名没接** ——
于是 `avatarUrl` 是裸 URL，前端渲染必然 403。现象：**帖子图能看、头像不能**。

**这是同一个 bug 形态第三次出现**："头像"这件事有三个消费者，
而它们被**逐个发现、每次漏一个**：

| # | 消费者 | 状态 |
|---|---|---|
| 1 | 签名下发（`GET /api/oss/signature?target=avatar`） | §14 做了 |
| 2 | 写入校验（`PUT /api/user/profile` 归属校验） | §14 做了 |
| 3 | **读取时签名** | §14 **漏了** → CR-Q |

前两次分别是"回调服务没接受 avatar 目录"（CallbackFailed）与"目录事实的消费者没列全"。

**为什么机械检查抓不到**：回调服务那处用的是 `imageUrlPrefix()` 这个**访问器**而不是字面量，
所以"搜目录字面量"这条检查永远看不到它。**同一事实的消费者必须靠结构约束，不能靠文本搜索。**

---

## 2. 结构堵法（L1 重点看的第一件事）

### 2.1 唯一装配入口

新增 `common/oss/AvatarUrlResolver`：**头像 URL 的唯一出口**。
所有对外暴露头像的地方都必须调它。

- 放在 `common.oss` 的理由：`post` / `interaction` / `notify` / `user` 四个包都要用，
  而它们之间禁止互相依赖（铁律 3）。与 `OssReadUrlSigner`、`common.audit.SensitiveTextChecker` 同一处置。
- 它自己判断"是否属于该用户目录"，前缀取自 `OssProperties.userAvatarUrlPrefix(userId)` ——
  与**签名下发**、**写入校验**同一来源（§14.2 ②）。

### 2.2 签名算法只有一段实现

新增 `common/oss/AvatarUrlSigner` 接口（`sign(userId, url)`），
`media` 的 `V1OssReadUrlSigner` 实现它，**与帖子图共用同一个私有方法 `signWithin(allowedPrefix, url)`**。

**为什么不复制一份签名算法**：签名串构造是本项目已知的高坑区（§12.3 记了三个坑：
`x-oss-process` 子资源、裸 URL vs 已签 URL、外部 URL）。抄一份等于把三个坑复制一遍，
而踩中任何一个的表现都是"URL 看起来带签名、但 GET 回 403"，**极难定位**（L1 自己的探针就误判过一次）。

### 2.3 ★ 新增一处时凭什么不会再漏：**因为它绕不过去**

新增 ArchUnit 规则 **`ARCH_avatar_url_must_go_through_resolver`**：

```
domain 与 common.oss 之外的类，禁止调用 User.getAvatarUrl()
```

- 新增装配点若直接读裸字段 → **代码能编译、接口也能跑**，但**架构门会红**；
- 豁免的只有两处，理由明确：`domain`（getter 的定义处）、`common.oss`（解析器的家）。

**我不接受"我记得"这个答案** —— 本任务已经证明它失败过三次。
这条规则把"记得"换成了"绕不过去"，而且**变异①已实测它真的会红**（见第 4 节）。

### 2.4 所有装配点的清单（L1 重点看的第二件事）

用 `git grep` 穷举 `avatarUrl` 的每一处出现后确认，共 **8 处**：

| # | 装配点 | 位置 | 改法 |
|---|---|---|---|
| 1 | 登录 / 注册响应的用户 | `UserVO.from` ← `AuthService`（3 处调用） | 走解析器 |
| 2 | 个人主页 | `UserProfileVO` ← `UserService.getProfile` | 走解析器 |
| 3 | PUT 资料的响应 | `UserProfileVO` ← `ProfileService.toProfileVO` | 走解析器（用刚写入的值） |
| 4 | 帖子作者 | `post.vo.UserBriefVO.from` ← `PostService`（2 处） | 走解析器 |
| 5 | 评论作者 | `interaction.vo.UserBriefVO.from` ← `CommentService`（2 处） | 走解析器 |
| 6 | 列表 / 侧栏作者 | 同 4、5（`FeedService`、`UserService` 的批量装配） | 走解析器 |
| 7 | **通知发送者** | `NotificationVO.from`（字段名是 `fromAvatarUrl`，**最容易漏**） | 走解析器 |
| 8 | 关注 / 粉丝列表 | `FollowUserVO` ← `FollowService` | 走解析器 |

**易漏点**：`UserBriefVO` 在本项目里有**两个不同的类**（`post.vo` 与 `interaction.vo`，
因为两个包不能互相依赖）—— 只改一个会"一半生效"，是最隐蔽的一处。

---

## 3. 三条边界（已写成断言）

1. `avatarUrl` 为 `null`/空白 → **保持 `null`**。绝不"签名一个空串"——
   那会让前端拿到一个非空的垃圾串，**回落默认头像的逻辑直接失效**。（空白串同样归为 null。）
2. **不属于本项目 OSS 前缀的 URL（历史数据、外链）→ 原样返回，不签名**。
   签名它没有意义，而且会让前端拿到一个"看起来是本站签名、实际必然 403"的链接，
   把"这是一条老数据"这件事**掩盖掉**。
3. 不属于**该用户自己**目录的（例如别人的 `avatar/{other}/`）→ 同样原样返回：
   它本来就不该出现在这个字段里（写入侧已挡），读侧不替越权数据"修好"成正常样子。

## 4. DoD 证据

### ① 红 → 绿

**绿**：`M4AvatarReadSigningTest` `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

**变异①（去掉某个装配点的签名 —— 通知发送者）→ 实测红，而且被抓住了两次**：

```
# ① 架构门先红：
Architecture Violation - Rule 'no classes that reside outside of package 'com.hyforum.domain..'
and reside outside of package 'com.hyforum.common.oss..' should call method User.getAvatarUrl()'
was violated (1 times):
  Method <com.hyforum.notify.vo.NotificationVO.from(...)> calls method
  <com.hyforum.domain.user.entity.User.getAvatarUrl()> in (NotificationVO.java:75)

# ② 覆盖面用例也红（响应里是裸 URL）：
M4AvatarReadSigningTest.M4_avatar_signing_covers_all_assemblers:169
  【通知发送者】的头像必须是**签名 URL**... 当前值：
  https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/avatar/2/head.png
```

**这是本轮最有价值的一条**：它同时证明了「结构防线（架构门）有效」与「覆盖面用例有效」，
而且架构门是**先**红的 —— 说明下次有人漏了装配点，会在**架构层**就被拦下，不必等到用例。

### ② 变异②：**我没能有效执行，如实说明**

L1 推荐的变异②是「把"非本站 URL 原样返回"改成"一律签名"→ 对应用例必须红」。

- **第一次尝试**（改 `AvatarUrlResolver` 不再判归属）：**用例仍绿**。
  原因：解析器的前缀判断与**签名器内部的前缀判断是重复的两层**，
  拆掉外层后内层仍然把外部 URL 原样返回 —— 所以这次变异**没有改变任何行为**，
  它不是一个有效的变异。
- **第二次尝试**（连签名器的前缀判断一起拆）：注入后**用例仍绿**，
  而排查发现是**我的工作区 `target/classes` 被变异实验污染**（`mvn compile` 未按预期重编译，
  见第 5 节），**变异根本没有进入运行的字节码**。

**结论：变异②本轮未能取得有效证据。** 我不会把它写成"通过"。
就我的判断，变异① 已经覆盖了"漏装配点"这一核心风险（且双重捕获），
而变异② 想验的"外部 URL 不被签名"这条断言**本身**是存在的、且在正跑里通过；
但它**没有被变异证明其有效性**。若 L1 认为必须补，我可以在一个干净构建里重做。

### ③ 全量（跑前/跑后 surefire 均 0）
```
ArchitectureRulesTest: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
Tests run: 114, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

### ④ 覆盖率脚本
两个用例名已登记（`c688db5`），「多余」清单里**已无我的名字**。

### ⑤ ⚠️ "真的能看到图"这条 —— **本环境做不到，没有伪造**

L1 要求"对签名 URL 真发一次匿名 GET，断言 200 + `Content-Type: image/*`"。
**本机没有任何 OSS 凭据**：`OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` / `OSS_ENDPOINT` /
`OSS_BUCKET` 全未设置；测试 profile 是占位值（`TEST-KEYID-1234567890` / `not-a-real-secret-...`）；
8080 上那个进程用的是同一份占位配置。

因此本机签出的 URL 是"**语法正确、密钥错误**"，真实 GET 必然 403 ——
而那是**凭据问题、不是签名逻辑问题**，用它判红绿会得出错误结论。
**本环境能交付的最强证据是**：签名 URL 带齐 `OSSAccessKeyId` / `Expires` / `Signature` 三个参数，
且**与帖子图走同一段实现、同一套参数、同一个 TTL 口径**（不是另设一套有效期）。
**"能取回字节"必须在有凭据的环境补做** —— 这一步我做不了，请你安排。

---

## 5. ⚠️ 本轮踩到的两个工程坑（比缺陷本身更值得记）

### 5.1 **变异实验污染了 `target/classes`，制造了一次"假红"**

排查变异②时出现 `GET /api/posts/{id}` 直接 500：
`StringIndexOutOfBoundsException: Range [50, 42) out of bounds for length 42`
栈顶在 `V1OssReadUrlSigner.signWithin:128`（`url.substring(publicPrefix.length())`）。

**大部分原因是我自己的构建污染**（清掉相关 class 强制重编译后即恢复），
但顺着这条栈暴露了一个**真实存在的脆弱点**，因此补了防御（`ff45259`）：
代码隐含依赖一条**没有强制的**不变量 ——"通过了前缀检查的 URL 一定以 `publicUrlPrefix` 开头、
因此一定不短于它"。一旦不成立，`substring` 就抛越界，而它发生在**读接口**里，
表现是「**整页 500**」而不是"这张图没签名"。现在改为原样返回（最坏这张图 403，不会整页 500）。

**教训**：变异实验会改源码，**必须确认"变异真的进了字节码"再解读结果**。
本轮我在这上面浪费了很多步，还差点把一个自己造出来的 500 当成产品缺陷。

### 5.2 `mvn clean` 在本机不可用

8080 上跑着的进程**锁住 `server/target/*.jar`**，`mvn clean` 报
`Failed to delete ...hy-forum-server-0.0.1-SNAPSHOT.jar`。
因此"清掉陈旧 class"只能**手动删具体 class 文件**再 `mvn compile` ——
本轮多次依赖这个动作，而它**不总是生效**（见 5.1）。
**建议**：本机跑变异实验前，先停掉 8080 或用独立 `-Dproject.build.directory`
（注意：该参数在本项目**实测不生效**，产物一律落 `server/target/`，见上一份报告 §7.1）。

---

## 6. 未做 / 待你决定

1. **真实桶上的 200 + `image/*` 验证** —— 需凭据，本环境无法完成（第 4 节⑤）。
2. **变异②未取得有效证据** —— 若必须补，请在干净构建下重做（第 4 节②）。
3. **孤儿头像对象**（`avatar/34/...` 等"传成功但界面说失败"留下的）—— 你已记入清理清单，我未处理。
4. `AVATAR_DIR` 保持常量（你的裁定不变）；§13 剩余项继续等。
