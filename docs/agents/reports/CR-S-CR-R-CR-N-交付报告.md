# CR-S / CR-R / CR-N 交付报告

> 提交：`6e6698c`（三件一起交，按 L1 要求"别分开交"）。
> 关联：`e02911c`（CR-Q 扩展：封面签名 + 收藏字段 + 护栏）、`067b2e0`/`fa70b9e`（前两份报告）。

---

## 1. CR-S：缩略图 403 —— 根因是**签名范围**，且我用真实凭据证明了它

### 1.1 根因（L1 已定性，我复核确认）

`OssUrls.thumbs()` 第一版是：

```java
String bareOriginal = sign(original);                       // ← 先签**裸地址**
result.add(sign(OssThumbnailUrls.derive(bareOriginal)));    // ← 再拼 &x-oss-process，然后"再签"
```

第二步的 `sign(...)` 对一个**已带签名**的 URL 调用时，`isAlreadySigned` 判定为真 →
**原样返回**。所以真正生效的签名只有第一步那个 ——
它算的是"**不含** `x-oss-process`"的 CanonicalizedResource，而请求里带着它 → 403。

### 1.2 ★ OSS 自己的报错就是铁证

我注入"错误顺序"后，OSS 返回：

```
Code: SignatureDoesNotMatch
StringToSign: GET\n\n\n1790255962\n/hy-forum-2026/post/cr-s/<key>.png?x-oss-process=image/resize,m_fill,w_360,h_360/quality,q_80
```

**它期望 `x-oss-process` 在待签字符串里** —— 与 L1 的结论逐字一致，不需要再推导。

### 1.3 修法

**derive 在前、sign 在后（只签一次）**：

```java
String thumbUrl = OssThumbnailUrls.derive(original);  // 先把处理参数拼进 query
...
result.add(sign(thumbUrl));                            // 只签一次，签名覆盖它
```

### 1.4 L1 要求的追问：「两条路为什么并存」—— 答案

项目里确实有两条"给带处理参数的 URL 签名"的路，但**算法是同一段**（都最终调 `V1OssReadUrlSigner.signWithin`），
差别只在**处理参数出现在签名之前还是之后**：

| 路 | 地址来源 | 形状 | 结果 |
|---|---|---|---|
| `PostService.toSignedImageVo` → `sign(vo.thumbUrl())` | **库里存的 thumb_url 就已带** `x-oss-process` | process **在签名前** | ✅ 正常（与前端实测 D 组一致：coverUrl 200） |
| `OssUrls.thumbs()`（我写的） | 从**原图**推导，参数要自己拼 | 我先签了裸地址、**process 在签名后** | ❌ 403（就是 A/B 组） |

**所以不是"两条算法"，而是"同一个算法在两种参数时序下被调用"** ——
这正是最容易被漏掉的形状：**三个签名参数齐全、URL 看起来完全正常**，
连"断言三参数存在"的护栏都放过去了（我上一版护栏就是这么漏的）。

**`OssUrls` 已是唯一入口，为什么还会错**：它确实是唯一入口，错在**入口内部的实现顺序**，
而不是"有人绕过了入口"。这一点值得记下来：把出口收成一处能防"漏调"，
但防不了"这一处自己写错" —— 后者只能靠**取回字节**的验证。

---

## 2. 护栏升级：`M4RealOssByteTest`（真的取回字节）

新增用例 `M4_thumbnails_are_signed_with_process_params`：

1. 用**真实凭据**签一次 PostObject 表单并上传一张**合法** 8×8 PNG（自包含，不依赖库里恰好有图）；
2. 经 `OssUrls.thumbs(...)` 得到缩略图 URL，对它**真发匿名 GET**；
3. 断言 **HTTP 200 + `Content-Type: image/*` + 非空字节**；
4. **反证**：构造成"签名只覆盖裸地址 + 带 process"的 URL，断言必须 **403**
   （证明本用例真的在验**范围**，而不是"只要 200 就算过"）；
5. 对照用例：同一对象**不带** process 的签名必须 200
   （把 403 的原因**锁定在签名范围**上，而不是凭据/前缀）。

**实测输出（全量里真的跑了，不是跳过）**：

```
[CR-S] 缩略图取回字节数=94，Content-Type=image/png
```

---

## 3. ⚠️ 我必须更正的一条错误结论：本机**有**真实凭据

**L1 指出我此前三次报"本机没有凭据"是错的 —— 这条更正成立，原因如下：**

```
Process 作用域：OSS_ACCESS_KEY_ID 不存在
User    作用域：OSS_ACCESS_KEY_ID 存在（形如 LTAI…，具体值不打码进文档）
```

> ⚠️ 本报告第一版把 AccessKey **ID 的完整值**抄了进来，被 `.githooks/pre-commit`
> （铁律 5 守卫）**当场拦下提交** —— 那个钩子是对的：密钥类字面值不该进仓库，
> 哪怕是"只是 ID 不是 Secret"。这里改为打码。
> **这条守卫本身值得记一句**：它把"文档里顺手抄一个凭据"也挡住了，
> 而文档往往是最容易被忽略的泄漏面。

`User` 作用域的值在注册表 `HKCU\Environment`，
**只对"之后新建的登录会话"生效** —— 我所在的会话进程环境里没有它，
所以 Maven 起的 fork 自然继承不到。**"进程级查不到"与"本机确实有凭据"同时为真**，
而我当时只查了进程级就下结论。这是我本轮最该记的一条方法论错误。

**为此给用例做了两个凭据来源**：
- 环境变量（CI 走这条）；
- `-Dhy.real.oss.creds=<临时 .properties>`（本机走这条）。

**为什么本机用文件而不是直接 `-D` 传密钥**：`-D` 会把 AccessKey Secret
**写进进程命令行**，同机其它进程在进程表里可见。文件只落在工作区、用后即删。

**缺凭据时**：`assumeTrue` 跳过并**打印原因**（含"注意 User 作用域"的提示），
不写成"在注释里说应该在真实环境验"。

### 3.1 顺带踩到的一个 fixture 坑（值得记）

第一版用的"1×1 透明 PNG"base64 是抄来的，OSS 图片处理报
**`ImageDamage / The image file may be damaged.`（HTTP 400）** ——
签名完全没问题，是**测试 fixture 本身不是合法图片**。
现象极容易被误读成"签名又坏了"。现在的 8×8 PNG 是用 System.Drawing 生成后导出的。

---

## 4. CR-R：点赞/收藏响应带回状态与计数

四个端点（点赞 / 取消 / 收藏 / 取消）现在都返回 `InteractionStateVO`：
`liked` / `collected` / `likeCount` / `collectCount`。

**两个刻意的设计选择**：

1. **计数从库里读，不在服务层"算"**：服务层知道"这次有没有真的插入关系行"，
   但**不知道并发下的最终值**（另一个请求可能同时点了赞）。所以读库，
   与列表接口同一口径 —— 前端两个来源不会有差异。
2. **`liked`/`collected` 是"相对于请求者"的**：用例里有反证 ——
   另一个人点赞后，我的 `liked` 仍为 false。

**为什么取消也返回状态**：否则"取消"后前端仍要自己猜按钮状态，同一个问题会以另一种形式回来。

## 5. CR-N：通知带 `postId` 与 `commentId`

| 通知类型 | `postId` | `commentId` |
|---|---|---|
| 点赞（1） | `targetId` | `null` |
| 评论（2） | `targetId` | `null` |
| **回复（3）** | **由评论的 `post_id` 反查** | `targetId` |
| 关注（4） | `null` | `null` |

- **批量反查，不做 N+1**：一次 `selectBatchIds` 把本页涉及的所有评论查出来。
- 评论已被删除时：`commentId` 仍给出（前端可提示"内容已删除"），`postId` 为 `null`，
  **不因此丢掉整条通知**（通知是"当时发生过"的记录，§5 第 3 条）。

**用例里踩到并记录的一点**：回复通知的接收人是**被回复的主楼作者**，不是帖子作者 ——
我第一版只查了帖子作者的收件箱，找不到 `type=3`。
实测两个收件箱分别是 `author: 4,2,1` 与 `actor: 3`。

---

## 6. DoD 证据

### 全量（跑前/跑后 surefire 均 0；**带真实凭据**跑，让"取字节"真的执行）
```
ArchitectureRulesTest: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
M4RealOssByteTest:     Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
M4InteractionResponseTest: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
Tests run: 122, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

### 变异（红 → 绿）

**变异：把 `OssUrls.thumbs()` 恢复成 CR-S 的错误顺序（先签裸地址、再拼 process）**：
```
[CR-S] thumb status=403 body=...<Code>SignatureDoesNotMatch</Code>
  <StringToSign>GET | | | 1790255962 | /hy-forum-2026/post/cr-s/....png?x-oss-process=image/resize,m_fill,w_360,h_360/quality,q_80</StringToSign>
Tests run: 2, Failures: 1
```
→ 还原后 **200**，字节 94，`image/png`。
（变异前后都删掉对应 class 强制重编译，确认变异**真的进了字节码**。）

**另一条变异（护栏层面）**：`e02911c` 里已验证过 —— 只改一处装配点，
护栏在**对应端点**上红（收藏 / 用户帖子列表）。

### 覆盖率脚本
CR-S / CR-R / CR-N 的用例名均已登记；「多余」清单里**没有我的名字**。

---

## 7. 未做 / 待办

1. **契约重导**：CR-R 的响应体由 `{}` 变成 4 个字段、CR-N 新增 2 个字段、
   `CollectionItemVO` 新增 2 个字段 —— 我只加注解，**未动 `openapi.json`**。
2. `M4RealOssByteTest` 在**无凭据环境**（如未配凭据的 CI）会 `assumeTrue` 跳过并打印原因 ——
   这一点是刻意的：宁可显式跳过，也不要把它写成"应该在真实环境验"。
   若希望 CI 也硬跑，需要在 CI 注入 `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`。
3. 我在本轮之前**三次误报"本机无凭据"**，已更正（见第 3 节）。同类错误的一般化教训：
   **下"环境里没有 X"这类结论前，要把作用域都查一遍**（Process / User / Machine），
   而不是只查当前进程。
