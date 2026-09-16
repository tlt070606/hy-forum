# 任务：W3 · M3 第二交付段（media：OSS 直传签名与回调验签）+ H12

> **范围以本文件为准。** 上一份任务书 [`W3-M3-版块与帖子与OSS.md`](W3-M3-版块与帖子与OSS.md) 的
> §11「第二交付段」只写了**范围梗概**，本文件把它落成可执行的口径（含 L1 的裁决、写权清单、
> 逐字测试名、以及这一段特有的**假绿陷阱**）。
> 两者冲突时**以本文件为准**（它更新）。
>
> 日期：2026-09-16 · 派给：**L2 实现者（后端）** · 状态：**待派发**

---

## 0. 你的角色与身份

你是 **L2 实现者**，只对**本任务书的范围**负责。你**不是**契约的主人：

- 契约（`openapi.json`、`docs/db/schema.sql`）的写权在 **L1**；你只加注解、不导契约。
- 文档（`docs/**`）的写权在 **L1**；你的产出通过**交付报告**回到 L1，由 L1 登记。
- 看板（[`../工作计划.md`](../工作计划.md)）**只有 L1 能写**。

**第一交付段是你自己做的**（提交 `6ddedc1`），已经 L1 独立复核通过（20 类 44 用例全绿）。
本段继续在同一个代码库上工作 —— **不要重写第一段的任何逻辑**，见 §3。

---

## 1. 必读（按顺序，别跳）

| # | 读什么 | 为什么 |
|---|---|---|
| 1 | [`../../adr/0007-OSS服务端签名直传.md`](../../adr/0007-OSS服务端签名直传.md) | 铁律 8 的落地方式；**本任务就是它的实现** |
| 2 | [`../../ops/OSS配置清单.md`](../../ops/OSS配置清单.md) | **§3.1–§3.5** 是三项前置的实测记录；**§5** 是 `ssh -R` 的坑；**§5.4** 是那条"借道别人静态站"的脆弱点 |
| 3 | [`../../技术方案.md`](../../技术方案.md) **§6.8／§8.4** | 端点定义与回调验签要求 |
| 4 | [`../../db/schema.sql`](../../db/schema.sql) 表 4 `post_image` | 你要写的表。注意 `post_id` 与 `audit_status` 的默认值 |
| 5 | [`../工作计划.md`](../工作计划.md) **§4 广播区** + **§6.5 交接事项** | 广播区里有 2026-09-16 的**新契约版本**与两条"绿色的假话"；§6.5 里有 **H12**（你的） |
| 6 | 本文件 **§5／§6／§10** | 裁决、DoD、效率授权 —— 这三节决定你写成什么样 |

**第一段的代码就是最好的说明书**：`post/service/PostService.java` 的
`resolveImageUrls` 与 `claimUnboundImages`（**认领**机制）、`domain/post/entity/PostImage.java`
的 `UNBOUND_POST_ID`、`post/image/ThumbnailUrls.java`。你这一段要和它们**无缝接上**。

---

## 2. 允许修改（写权清单 —— **超出即越界**）

| 路径 | 允许的动作 |
|---|---|
| `server/src/main/java/com/hyforum/media/**` | **新建**：签名接口、回调接口、验签器、凭据绑定、相关 DTO/VO |
| `server/src/main/java/com/hyforum/common/oss/OssProperties.java` | **从 `post/config/OssProperties.java` 迁入**（见 §5 裁决 ①）。迁入后**只允许加文档注释**，逻辑保持等价 |
| `server/src/main/java/com/hyforum/post/config/OssProperties.java` | **删除**（连同该文件） |
| `server/src/main/java/com/hyforum/post/service/PostService.java` | ⚠️ **只许改 import 一行**（`com.hyforum.post.config.OssProperties` → `com.hyforum.common.oss.OssProperties`）。**不得改任何逻辑** |
| `server/src/main/java/com/hyforum/common/web/GlobalExceptionHandler.java` | **仅**新增 `MethodArgumentTypeMismatchException` 的处理（**H12**，见 §6.2）。**不得顺手整理其它异常分支** |
| `server/src/test/java/com/hyforum/media/**` | **新建**测试 |
| `server/src/test/java/com/hyforum/common/**` | **新建** H12 的测试（放 `common` 下，因为它测的是公共层的异常处理） |
| `server/src/main/resources/application.yml` | 🚫 **只读不写**。`aliyun.oss` 一节由 **L1 维护**；你需要新配置项时**提 CR**，不要去加 |

---

## 3. 禁止

1. **禁止手改 `openapi.json`**、禁止手改 `web/src/api/generated/**`（L1 独占）。
2. **禁止改 `docs/db/schema.sql`**：`post_image` 现有的列与默认值**够用**，不需要新列。真觉得不够 → 提 CR。
3. **禁止重写第一交付段的图片逻辑**：`resolveImageUrls`（前缀归属校验 / fail-closed）、
   `claimUnboundImages`（认领）、`ThumbnailUrls`（缩略图 URL）**都是已复核过的**。
   你的回调只需**按它们已有的约定落库**（`post_id = 0` + `audit_status = 0`）。
4. **禁止把 AccessKey 写进任何被 git 跟踪的文件**（铁律 5；`.githooks/pre-commit` 与 CI 都会拦）。
   密钥只从**环境变量**来。
5. **禁止"为了先跑通"造一个假签名**，或把回调改成前端确认制（那要改 ADR-0007，且破坏铁律 8）。
6. **禁止在 `common/oss/OssProperties` 里绑定凭据字段** —— 见 §5 裁决 ①。
7. **禁止为让测试变绿而把测试改弱**（删断言、放宽等值、`@Disabled`）。**测试是交付物的一部分。**

---

## 4. 环境前置（**三项已实测通过，不要再花时间探测**）

| 前置 | 状态 | 依据 |
|---|---|---|
| RAM 授权（签名可用） | ✅ 实测通过 | `OSS配置清单.md` §3.4：匿名 PUT → 403、**带签名 PUT → 200** |
| Bucket CORS | ✅ 实测通过 | 同上：预检与带 `x-oss-*` 头的 PUT 均可 |
| 公网回调可达 | ✅ 实测通过 | 同上：`http://8.138.237.212/api/oss/callback` 可达（经 ECS:80 → `ssh -R` → 本机 8080） |

- **环境变量在【用户级】**（`OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` / `OSS_ENDPOINT` / `OSS_BUCKET`），
  不在仓库里。新开的 shell 里需要显式取一次：
  ```powershell
  $env:OSS_ACCESS_KEY_ID = [Environment]::GetEnvironmentVariable('OSS_ACCESS_KEY_ID','User')
  $env:OSS_ACCESS_KEY_SECRET = [Environment]::GetEnvironmentVariable('OSS_ACCESS_KEY_SECRET','User')
  ```
- **隧道会断**（根因未定位）：断了用
  `powershell -NoProfile -File scripts\start_oss_callback_tunnel.ps1` 重起（自带重连）。
- ⚠️ **借道那条路是脆的**：ECS 上那个 `personal` 静态站的 nginx 配置里加了一行 `include`。
  若那个站被重新部署，`include` 会消失，**现象是"上传成功但图片不落库"，看起来像后端 bug**。
  排查时先看那行在不在（`OSS配置清单.md` §5.4）。

---

## 5. L1 已裁决（**照办，不要再重新讨论**）

① **`OssProperties` 上移到 `com.hyforum.common.oss`**，`post` 与 `media` **都注入它**。
   理由：同一个配置前缀若由两个包各自绑一份，就是"同一事实两处映射"，迟早漂移；
   而漂移的表现是"图片能传上去但前台不显示"这类极难定位的现象。
   **不要**让 `post` 依赖 `media` 或反之（铁律 3，`ARCH_no_cross_module_dependency` 会拦）。
   ⚠️ **凭据不进这个类**：`post` 也注入它，而 `post` 只需要 `endpoint / bucket / imageDir` 三个非密钥项。
   两个密钥请放在 **`media` 包自己的属性类**里（同一前缀 `aliyun.oss`，Spring 允许多个属性类绑同一前缀）。
   这是**最小暴露**，不是洁癖：密钥被不相关的模块读到，将来就会有人在不相关的地方用它。

② **两个密钥必须 `@NotBlank`（fail-fast）**，否则 `application.yml` 里那句
   「两个密钥**故意留空**：没设环境变量时启动即失败」**是假的** ——
   **实测：当前不设任何 OSS 环境变量，应用照样启动成功**（2026-09-16 由 L1 实测，端口 8080 正常服务）。
   本段要么把这句话兑现，要么它就该被删掉。**兑现是更好的选择**：快速失败远好于
   "带着空密钥正常启动、直到用户上传时才失败"。

③ **回调端点的鉴权**：`POST /api/oss/callback` 由 OSS 发起，**没有登录态** → 必须匿名放行，
   安全性**完全由验签承担**。这与"可选鉴权的实现例外"（H13）不是一回事：
   回调**不需要**当前用户，别为了拿到用户去解析 token。
   `GET /api/oss/signature` 则**必须登录**（未登录 → 401）。

④ **回调的落库口径**（第一段已定，照接）：先以 `post_id = 0`（`PostImage.UNBOUND_POST_ID`）落库，
   发帖时由 `claimUnboundImages` 按 URL 认领。**不要把真实 `post_id` 塞进回调** ——
   回调发生的那一刻帖子还不存在。

⑤ **H12 一并修**（见 §6.2）。它属于 **M1 的既有缺陷**，修它要**跑全量回归**。

---

### 5.6 回调验签的**权威口径（L1 已钉死 —— 不要凭记忆实现，也不要再去搜）**

来源：阿里云官方文档 [Callback（回调）](https://help.aliyun.com/zh/oss/developer-reference/callback)。
L1 于 2026-09-16 **抓取原文**后摘出下面这份口径（不是凭记忆转述）。**这一节的存在就是为了让你不必再花一轮去查它。**

**OSS 侧怎么签的（原文公式）**

```
authorization = base64_encode(rsa_sign(private_key, url_decode(path) + query_string + '\n' + body, md5))
```

**你侧怎么验（四步，顺序不能变）**

1. 取 `x-oss-pub-key-url` 头 → **Base64 解码**，得到公钥 URL。
   例：`aHR0cDovL2dvc3NwdWJsaWMuYWxpY2RuLmNvbS9jYWxsYmFja19wdWJfa2V5X3YxLnBlbQ==`
   → 解码为 `http://gosspublic.alicdn.com/callback_pub_key_v1.pem`
2. 🔴 **先校验公钥 URL 的前缀必须是 `http://gosspublic.alicdn.com/` 或 `https://gosspublic.alicdn.com/`；不满足直接拒绝。**（原文明确要求；理由见下方"安全闸门"）
3. 取 `authorization` 头取值 → **Base64 解码**，得到签名。
4. 用 **MD5 + RSA 公钥**验签：`rsa_verify(public_key, md5(url_decode(path) + query_string + "\n" + body), signature)`

**原文给出的回调请求头**（可用于自检你的解析是否对路）：`POST`，带 `Content-MD5`、`x-oss-bucket`、`x-oss-pub-key-url`、`x-oss-signature-version: 1.0`、`x-oss-tag: CALLBACK`、`User-Agent: aliyun-oss-callback`。
原文另提到**公钥建议缓存**（避免网络波动影响性能）—— 但缓存**必须与第 2 步的域名白名单同时成立**，不能因为"缓存过了"就跳过校验。

#### 🔴 为什么第 2 步是安全闸门（**这条比算法本身更重要**）

如果实现成"头里给什么 URL 就去取什么公钥"，那么：

- **验签可被绕过**：攻击者**自带一对密钥** —— 用他自己的私钥签、再把他自己的公钥地址放进 `x-oss-pub-key-url`，你的验签**会全部通过**；
- **同时是一个 SSRF**：你的服务器会被指去访问任意地址（内网、云元数据端点）。

**所以：验签的正确性 = 算法对 **且** 信任锚点对。只做对前一半，测出来的绿是假的。**

因此 `M3_oss_callback_rejects_bad_signature` **必须两个方向都打**：

- ① 合法公钥地址 + **伪造签名** → 拒绝、**不落库**；
- ② **伪造公钥地址**（如 `http://evil.example.com/pub.pem`，或用与合法域名相近的仿冒域名）+ 攻击者自签的"看起来合法"的签名 → **必须拒绝**（这一条专打 SSRF 与信任锚点）。

#### 顺带确认 CR-007 的裁决理由

原文明确「之后 OSS 会将该响应内容**透传给客户端**」—— 即**回调的响应体会被上传方（前端）收到**，所以它确实是对外行为，"用项目统一响应体"这个裁决成立。

---

## 6. 完成定义（DoD）—— 必须贴出**真实命令输出**

### 6.1 端点（来源 [`../../技术方案.md`](../../技术方案.md) §6.8）

| 方法 | 路径 | 鉴权 | 要点 |
|---|---|---|---|
| GET | `/api/oss/signature` | **是**（未登录 401） | 返回 `{host, policy, signature, dir, expire, callback}`；`dir` **必须与 `OssProperties.imageDir` 同源**（否则签名的目录与后端校验的前缀不一致 → 前端传上去的图后端不认） |
| POST | `/api/oss/callback` | **匿名，但必须验签** | 验签通过 → 写 `post_image`（`post_id=0`、`audit_status=0`）；验签失败 → **拒绝且不落库** |

契约会从 **12 路径 → 14 路径**（L1 重导，见 §7）。

### 6.2 测试方法名（**逐字一致**，`check_test_coverage_gaps.ps1` 靠它防漏测）

| # | 测试方法名 | 断言要点 |
|---|---|---|
| 1 | `M3_oss_signature_requires_login` | 未登录 → **401**；且**反证**：登录后同一请求 → 200 且字段齐全（否则"401"可能只是接口不存在） |
| 2 | `M3_oss_callback_rejects_bad_signature` | 伪造签名 → 拒绝、**库里 `post_image` 行数必须不变**（只回错误码却照样落库 = 验签没生效）。**两个方向都要打，见 §5.6**：① 合法公钥地址 + 伪造签名；② **伪造公钥地址** + 自签的"合法"签名（专打 SSRF 与信任锚点 —— 只做算法不做域名校验的话，①照样绿，而验签可被完全绕过） |
| 3 | `M3_oss_callback_then_publish_claims_image` | 回调先落库（`post_id=0`）→ 发帖带上该 URL → **该行被认领**（`post_id` = 新帖 id、`sort` 与请求顺序一致、`audit_status=0`）→ 详情页 `images` 里能看到它。**这是本段最容易做成假绿的用例**，见下面的 ★ |
| 4 | `M1_path_variable_type_mismatch_returns_400` | **H12**：`GET /api/posts/abc` → **HTTP 400 + 统一响应体**。**必须带反证**：未知路径仍是 **404**（否则"把什么都变成 400"也能让它通过，而那破坏了契约的 404 语义） |

> ★ **本段的核心假绿陷阱（必读）**：如果验签器实现成"永远拒绝"，那么用例 2 **必然通过**，
> 而整条链路**永远没有一张图片能落库**。所以**必须有正向路径的用例**，且
> **验签器要可替换**（接口 + 测试替身，或让公钥地址可注入指向本地桩）。
> 同理：`M3_oss_callback_then_publish_claims_image` 若只断言"回调返回成功"，
> 而回调其实没落库，用例 3 也会绿 —— 它必须**查库**。

### 6.3 必交证据（缺一条就算没完成）

1. **「红 → 绿」两次输出**：先写测试跑出失败并贴输出，再实现跑通并贴输出。
   **一个从未失败过的测试不能证明它在测任何东西**（[`../../testing/README.md`](../../testing/README.md) §2）。
2. **反向验证（变异）至少两条**，每条都要「改坏 → 变红 → 改回 → 转绿」三次输出。
   **推荐这两条**（它们正好覆盖上面那个假绿陷阱）：
   - 把验签器改成**永远通过** → `M3_oss_callback_rejects_bad_signature` **必须变红**；
   - 把回调里落库的 `post_id` 改成"猜一个真实 id"或干脆不落库 →
     `M3_oss_callback_then_publish_claims_image` **必须变红**。
3. **全量 `mvn test`**：M1 的 23 项 + 第一交付段的 44 用例 **必须仍全绿**。
   本段动了 `common/web`（H12）与 `post`（import）→ **这是唯一有回归风险的地方，别省这一步**。
4. `powershell -File scripts/check_test_coverage_gaps.ps1 -Detailed` 的输出**末尾**：
   「多余」清单里**不应再有本段的测试名**（L1 已把 §6.2 的 4 个名字预先登记进映射表）。
5. `git status --short` + 逐文件改动清单（**对照 §2 写权清单逐个说明**）。
6. **一次真实回调的证据**：隧道在跑的前提下，用真实签名走一遍
   `PUT` 到 OSS → OSS 回调 → 查 `post_image` 有新行。**贴出 `post_image` 的行内容**
   （URL、`post_id`、`audit_status`）。**截图不算，SQL 输出算。**
7. 报告里**逐条说明**：§6.2 的 4 条哪些跑了、哪些没跑、没跑的**为什么**。
   **没验证就说没验证** —— 本项目的验收只认命令输出，不认"基本上做完了"。

---

## 7. 契约链（**你只做第 1 步**）

```
① 你在 Controller/DTO 上加注解（你的活）
② L1 跑 scripts/export_openapi.ps1 → 重导 openapi.json（12 → 14 路径）并提交
③ L1 重新生成 web/src/api/generated/schema.d.ts 并跑 check:contract
④ 前端 `vue-tsc --noEmit` 若报错 → 说明前端要跟上（交回前端任务）
```

> ⚠️ `scripts/export_openapi.ps1` 现在带**端口占用前置检查**：8080 被占用时会直接失败并点出占用者。
> 这是刻意的 —— 它原先可能把**旧实例的契约**当成新的写下去并报成功。
> 排查"契约怎么少了一个路径"之前，先确认导出时 8080 上跑的**是你这次的构建**。

---

## 8. 交到你头上的交接事项

| # | 事项 | 为什么不能省 |
|---|---|---|
| **H12** | `GET /api/posts/abc` 返回 **500 而非 400**（`MethodArgumentTypeMismatchException` 未被单独处理） | 它把"客户端传错参数"报成"服务器内部错误"，前端会按 5xx 重试/告警，而真相是 400；5xx 是监控口径里的异常信号，被污染后**真故障会被淹没**。这是 M1 的既有缺陷，**加这一条要跑全量回归** |
| H13 | 可选鉴权的实现例外（`@AllowAnonymous` + 显式取 `currentUserId`）**本段不处理** | 收敛它是独立小任务（动 `common/web`）。**本段不要顺手改** —— 回调不需要当前用户（见 §5 裁决 ③） |
| H4 | 敏感词的完整能力（DFA／图片审核／队列）**不归你** | 归 M5。你在回调里**不涉及**敏感词判定：图片的 `audit_status` 一律写 `0`（尚未人工判定），可见性规则见 CR-006（**隐藏 `=2`**，不是"必须为 1 才显示"） |

---

## 9. 契约不足或环境受阻时怎么办

- **契约不足**（缺字段／缺接口／语义不明）：**不改契约、不绕过** → 结束本轮并报告 `BLOCKED + CR 内容`。
- **环境受阻**（隧道不通、密钥缺失、CORS 变了）：报 `BLOCKED + 具体缺哪一项`，
  **不要**造假签名、不要改 ADR-0007。
- **需要动 §2 之外的文件**：停下来报告，**不要自己扩范围**。

---

## 10. 效率授权（L1 已批）**以及它的边界**

上一段你证明了自己能在不降标准的前提下压缩时间。本段**继续授权**这四条：

1. **单类迭代测试**：不要每改一行就跑全量 `mvn test`。用 `mvn test -Dtest=<类名>` 快速迭代，
   **收尾时再跑一次全量**（§6.3 第 3 条要求全量输出）。
2. **注释写"决定 + 条款指针"**，不要复述文档：例如
   「回调先落 `post_id=0`，发帖时按 URL 认领 —— 见 `PostService.claimUnboundImages`」。
   **不要**把 `schema.sql` 的列定义抄进 Java 注释（那会造出第二份会过期的事实）。
3. **少问阻塞性问题**：实现层面"这段代码怎么写"这种读法问题，自己定，**在报告里写明你的取舍**即可。
4. **可以并行写文件**。

> 🚩 **边界（这一条比上面四条都重要）**：
> 「少问」**只适用于实现层面的读法不确定**。
> **凡是"换一种读法就换一种对外行为"的，必须停下来提 CR** ——
> 例如：回调成功该返回 OSS 要求的哪种响应体、验签失败该返回 400 还是 403、
> `expire` 的单位是秒还是毫秒、`dir` 带不带尾斜杠。
> 这类问题**自己选一个读法也能让测试全绿**，而错误会**沉进测试里** ——
> 那时你有的是"绿的测试"和"错的契约"，**后者会污染前端与后续所有实现**。

### 10.1 🆕 单轮上限与「中途可见」（2026-09-16 追加，**硬约束**）

上一轮暴露的问题：一个 turn 跑 **127 步、工具调用 45 分钟**，需求方在外面看着像卡住，**按了中断**，把工作掐在中间。这不是你的错（是我的任务书没给约束），但从现在起：

1. **单轮步数上限 ≈ 70 步**，或**单个工具调用上限 ≈ 10 分钟**（两者先到为准）。到线就**停下交付一次**：说清"已完成什么、下一步做什么、卡在哪"。
2. **每完成一个可编译的单元就落盘**（不要攒到最后一次性写）。宁可交付一个"能编译、测试还没跑"的中间态，也不要交一个 50 分钟的静默。
3. **交付是分段的**：允许你在整段没做完时先交付一次，说清剩余部分。**"基本上做完了"不算交付，中间态可以算。**
4. 真被外部中断（例如需求方按停）时，**下一次开工先看自己落在磁盘上的东西**，别从头再来。

### 10.2 🆕 最小闭环优先（2026-09-16 追加）

**先把"上传一张图 → 它能出现在帖子里"这条**端到端**路径跑绿**（`M3_oss_callback_then_publish_claims_image`），**再**补边界（多图、异常分支、缩略图、缓存、性能）。

理由：这条路径一旦通，"M3 的验收能不能达成"就有答案了；而边界补得再全，闭环不通就一分验收都拿不到。**不要**先把所有分支写完再第一次跑端到端 —— 那样你会在最后一刻才发现"签名目录与后端前缀对不上"这类跨模块问题。

---

## 11. 交付后

1. **停下**，不要自行开始 M4（第二段之后是 M4，由 L1 派）。
2. 交付报告请按 [`../任务书模板.md`](../任务书模板.md) 的格式，并**显式包含**：
   §6.3 的 7 项证据、§6.2 的 4 条测试逐条状态、以及**你改了 §2 清单之外的任何文件都要单独说明**。
3. **不要自己 push**。提交由 L1 复核后处理（第一段就是这么走的）。

---

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-16 | 首版。由 [`W3-M3-版块与帖子与OSS.md`](W3-M3-版块与帖子与OSS.md) §11 的"第二交付段"细化为可执行任务书，并写明五项 L1 裁决（`OssProperties` 上移 `common.oss` + 凭据不进该类、密钥 `@NotBlank` fail-fast、回调匿名但必须验签、回调落 `post_id=0` 由发帖认领、H12 一并修）、**本段的核心假绿陷阱**（验签器永远拒绝 ⇒ 拒绝用例必然绿）、以及**效率授权的边界**（换读法即换对外行为的必须提 CR） |
