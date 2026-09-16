# 任务：W3 · M3 版块 + 帖子 + OSS

> 派发给：L2 后端实现者（1 个，**与 W3-H10 并行，写权已互斥切分**）
> 生成者：L1　｜　生成时间：2026-09-15
> 协作状态登记见 [`../工作计划.md`](../工作计划.md)（**开工前必读 §4 广播区**）
> 前置与阻塞分析见 [`../M3-计划与前置.md`](../M3-计划与前置.md)；OSS 配置见 [`../../ops/OSS配置清单.md`](../../ops/OSS配置清单.md)

---

## 0. 你的角色与身份

- **角色**：L2 后端实现者
- **影响范围**：`server/src/main/java/com/hyforum/post/**`、`server/src/main/java/com/hyforum/media/**`，以及你为它们写的测试
- **上下文说明**：你看不到派活方的对话历史。**所有约束在下面的「必读」里**，不要去猜，也不要用你的默认习惯替代项目约定。

---

## 1. 必读

| 文档 | 定位 | 为什么 |
|---|---|---|
| `AGENTS.md` | 铁律 **1、3、4、5、7、8** | 尤其 **8：上传必须走 OSS 服务端签名直传，禁止后端中转** |
| [`../../PLAN.md`](../../PLAN.md) | §4 的 **M3 行** | **这就是你的验收标准** |
| [`../../技术方案.md`](../../技术方案.md) | **§5 `post`／`post_image` 表**、**§6.5 帖子模块**、**§6.8 OSS 模块**、**§8.4 上传规则**、**§8.6 内容审核**、**§8.7 频率限制**、**§14**（术语） | 接口形状、业务规则、状态机 |
| **`openapi.json`（仓库根）** | **8 个路径** | 接口契约的唯一事实来源。**你要新增约 8 个路径**（见 §6） |
| [`../../adr/0007`](../../adr/0007-OSS服务端签名直传.md) | 全文 | 为什么是服务端签名直传，以及**不得改成前端确认制** |
| [`../../adr/0008`](../../adr/0008-网盘链接与提取码分离存储.md) | 全文 | `diskUrl` / `diskCode` 的归一化规则 |
| [`../../testing/README.md`](../../testing/README.md) | **§2 三条纪律**、**§5 命名与防漏测**、**§5.1 两个坑** | 测试怎么写才算数 |
| [`../README.md`](../README.md) | **§7 工程约定** | PowerShell 5.1 / BOM / JDK / 编码 |
| [`../工作计划.md`](../工作计划.md) | **§4 广播区（每一条）**、§6.5 交接事项 | 那里有别人踩过的坑，以及**明确交到你头上的 H1** |

---

## 2. 允许修改

- `server/src/main/java/com/hyforum/post/**`（版块、帖子、网盘字段归一化）
- `server/src/main/java/com/hyforum/media/**`（OSS 签名与回调；该包已有 `PackageMarker.java`，是你的）
- `server/src/main/resources/application.yml` —— **仅限新增 OSS 配置项的占位（值一律走环境变量）**
- `server/src/test/java/com/hyforum/post/**`、`server/src/test/java/com/hyforum/media/**`（新建）
- **CR-005 的例外范围**（见 §7.3，**只许动这两处**）：
  `server/src/main/java/com/hyforum/auth/**` 中与 `captcha` / `register-mode` **返回类型**相关的部分

> **写权互斥**：`server/src/main/java/com/hyforum/auth/**` 的其余部分、`common/**`、`domain/**` 都**不属于你**（M1 已交付并通过复核）。动它们需要先提 CR。

## 3. 禁止

- 修改 `docs/**`（L1 独占）、`openapi.json`（L1 独占，见 §7）、`docs/db/schema.sql`（走冻结流程）
- 修改 `web/**`（那是 W3-H10 的写权范围）
- **后端中转文件上传**（铁律 8）。签名由后端出，字节流必须前端直传 OSS
- **硬编码任何 AccessKey / Bucket / 域名**（铁律 5）。一律从环境变量读
- 引入 Elasticsearch / RabbitMQ / Kafka / 注册中心（铁律 7）
- 跨模块调用：**不许依赖其他业务包的 Mapper 或 Service 实现**（铁律 3，由 ArchUnit 守门）
- 让测试类以 `IT` 结尾 —— **Surefire 默认不跑 `*IT.java`**，测试会**静默跳过**（见 §5.1 坑一）
- 「先这么写、回头对齐契约」式的临时约定

---

## 4. 环境前置

| 前置 | 状态 | 说明 |
|---|---|---|
| JDK 21 | ✅ | **每条命令前必须 `$env:JAVA_HOME='D:\develop\jdk21'`**（宿主进程持旧环境，子进程会继承 jdk17 → 报 `invalid target release`） |
| MySQL / Redis | ✅ | `127.0.0.1:3306`（root/1234）、`192.168.100.128:6380` |
| 后端可构建 | ✅ | `cd server; mvn clean package` |
| **OSS 已开通** | ✅ | Bucket 与 RAM 子账号已具备 |
| **OSS 配置** | ⚠️ **由需求方按 [`../../ops/OSS配置清单.md`](../../ops/OSS配置清单.md) 落实** | 你开工前先确认三件事：① 环境变量 `OSS_*` 已设；② Bucket 的 **CORS** 已放行前端源；③ **`ssh -R` 反向隧道**已开（OSS 回调要打得到本机） |

**你要用的环境变量名（与真机对齐后写进 `application.yml` 的占位）**：`OSS_ACCESS_KEY_ID`／`OSS_ACCESS_KEY_SECRET`／`OSS_BUCKET`／`OSS_ENDPOINT`。
**先探测这三项是否就绪再动手**；缺任何一项 → 按 §9 报 `BLOCKED`，**不要自己编一个假值绕过去**。

---

## 5. L1 已裁决的四条（**不要自己猜，按这里写**）

这四条要么是契约里原本歧义，要么是实现者最容易想当然的地方。**改动它们要提 CR。**

### 5.1 发帖后的 `status` 是什么

- **未命中敏感词 → `status = 1`（直接可见）**。这是 §8.6 第 5 条明文的「**先发后审**」降级策略。
- **命中敏感词 → `status = 0`（待审核，前台不可见）**，见 §8.6 第 2 条。
- 所以：**发帖不是一律待审**。若你写成"发帖一律 `status=0`"，M3 的验收标准（详情页能看到）就永远达不成。
- 敏感词检查**用 M1 已交付的实现**（`InMemorySensitiveTextChecker`）。**不要在本任务里重写它** —— 它的完整能力（DFA／图片审核／审核队列）归 M5（见 §6.5 的 H4）。

### 5.2 图片的可见性（**这条最容易搞错**）

`post_image.audit_status` 的语义已于 **2026-09-15 澄清**（见 [`../../技术方案.md`](../../技术方案.md) §8.6 第 4 条，登记为 CR-006）：

```
0 = 尚未被人工判定（默认）   1 = 已人工确认通过   2 = 已判定违规
前台可见性规则：隐藏 audit_status = 2 的图片；**不过滤 0**
```

**不要把"默认 0"理解成"必须人工放行才可见"** —— 本期没有图片机审能力，那会导致每张图都要人工点一次，与先发后审矛盾，且 M3 验收无法达成。**图片的审核出口（置 1／2）归 M5/M6，你只在写回调时落库 `audit_status`，不做前台过滤之外的事。**

### 5.3 编辑重审（状态机闭环，§8.6 第 6 条）

`PUT /api/posts/{id}` 只要**内容有变更**（标题／正文／图片／网盘字段任一项），**`status` 一律回到 0**。
这是堵「先发合规内容过审、再改成违规内容」的绕过路径。**不要写成"只在新内容命中敏感词时才回 0"** —— 那是绕过。

### 5.4 浏览量 `+1` 走 Redis

`GET /api/posts/{id}` 的浏览量自增**走 Redis**（§6.5），不是每次 `UPDATE post SET view_count = view_count + 1`。
原因见 `deployment.md`：高频写会放大行锁与磁盘压力。**具体做法（Redis 计数 + 定期回写，还是读时合并）由你设计并在代码注释里写明取舍**，但必须是 Redis。

---

## 6. 完成定义（DoD）—— 必须贴出**真实命令输出**

### 6.1 端点清单（来源 [`../../技术方案.md`](../../技术方案.md) §6.5／§6.8）

| 方法 | 路径 | 鉴权 | 要点 |
|---|---|---|---|
| GET | `/api/boards` | 否 | 版块列表（含 `isResource` 标记） |
| GET | `/api/posts` | 否 | `boardId`、`sort=latest\|hot\|essence`、`page`、`size` |
| GET | `/api/posts/{id}` | 可选 | 详情；浏览量 +1（走 Redis） |
| POST | `/api/posts` | 是 | 资源版需 `diskType`、`diskUrl`，`diskCode` 可选 |
| PUT | `/api/posts/{id}` | 是 | 仅作者，限发布后 30 分钟内；**内容变更 → `status` 回 0** |
| DELETE | `/api/posts/{id}` | 是 | 仅作者或管理员，逻辑删除 |
| GET | `/api/posts/search` | 否 | `keyword`，检索标题与正文 |
| GET | `/api/oss/signature` | 是 | 返回 `{host, policy, signature, dir, expire, callback}` |
| POST | `/api/oss/callback` | **OSS 回调，需验签** | 验签后写 `post_image` |

> **点赞 / 收藏不属本任务**（`POST|DELETE /api/posts/{id}/like`、`/collect` 归 M4）。

### 6.2 验收项 → 测试方法名（**逐条都要有，名字必须一模一样**）

清单取自 [`../../testing/验收项-测试映射.md`](../../testing/验收项-测试映射.md) 的 M3 一节。
**纪律一：给清单，不给"帮我写测试"。** 名字必须逐字一致 —— `scripts/check_test_coverage_gaps.ps1` 靠它防漏测。

| # | 测试方法名 | 断言要点 |
|---|---|---|
| 1 | `M3_publish_resource_post_with_images` | 落库 + 详情页字段完整 |
| 2 | `M3_disk_url_normalized_with_pwd` | 拆出 `disk_code`、URL 去掉 `pwd` |
| 3 | `M3_disk_url_normalized_without_pwd` | `disk_code` 保持空 |
| 4 | `M3_disk_code_may_be_empty` | 阿里云盘／夸克场景可保存 |
| 5 | `M3_copy_payload_format` | 链接行 / 提取码行 / 来源行 |
| 6 | `M3_reject_external_image_url` | 非本项目 OSS 前缀 → 拒绝 |
| 7 | `M3_oss_signature_requires_login` | 未登录 → 401 |
| 8 | `M3_oss_callback_rejects_bad_signature` | 伪造签名 → 拒绝、**不落库** |
| 9 | `M3_image_count_capped_at_nine` | 第 10 张 → 拒绝 |
| 10 | `M3_list_returns_summary_only` | 响应**无正文字段** |
| 11 | `M3_page_size_hard_cap_20` | `size=100` → 实际 20 |
| 12 | `M3_list_uses_thumb_url` | 列表返回的是**缩略图** URL |
| 13 | `M3_only_author_can_edit_within_30min` | 越权 / 超时 → 拒绝 |
| 14 | `M3_post_rate_limit_triggers_2002` | **H1，见 §8**：真实触发 `2002 发帖过于频繁` |

### 6.3 必交证据（缺一条就算没完成）

1. **`mvn test` 的「红 → 绿」两次输出** —— 先写测试跑出失败并贴输出，再写实现跑通并贴输出。**这是一个从未失败过的测试不能证明它在测任何东西**（[`../../testing/README.md`](../../testing/README.md) §2 纪律二）。
2. **反向验证至少两条**（纪律三）：挑两条最关键的用例（推荐 `M3_oss_callback_rejects_bad_signature` 与 `M3_only_author_can_edit_within_30min`），**故意把实现改坏** → 确认用例**变红** → 改回来。
   注意：`M1LogoutTest` 那种"内置反证步骤"的做法值得照抄 —— 先断言正常路径成立，再断言异常被拒，避免"功能本来就没生效"造成的假绿。
3. **`powershell -File scripts/verify_m0.ps1` 与 `scripts/check_test_coverage_gaps.ps1 -Detailed` 的输出** —— 后者用来证明你**没漏测**。
4. `git status --short` + 逐文件改动清单。
5. **`python` 校验 `openapi.json` 导出成功**：由 L1 执行（见 §7.2），你只需报告"端点已按 §6.1 全部实现、注解已加"。

---

## 7. 契约变更流程（**合并进同一仓库之后，这一步变了**）

### 7.1 为什么这块现在更严格

2026-09-15 前端已合并进本仓库 `web/`，契约回到**仓库根一份**。而且新增了 CI 闸门：
**契约一改、前端类型没跟上 → `.github/workflows/ci-web.yml` 直接红**。
所以"改契约"不再是改一个文件，而是一条链（见 §7.2）。

### 7.2 契约链（**你只做第 1 步，其余归 L1**）

```
① 你在 Controller/DTO 上加注解（你的活）
② L1 跑 scripts/export_openapi.ps1 → 重导 openapi.json 并提交
③ L1 重新生成 web/src/api/generated/schema.d.ts
④ 前端 `vue-tsc --noEmit` 若报错 → 说明前端要跟上（交回前端任务）
```

**禁止**：自己手改 `openapi.json`、自己手改 `web/src/api/generated/**`。
**发现契约不足以完成任务 → 停下来提 CR**，见 §3（[`../工作计划.md`](../工作计划.md) §3）。

### 7.3 你本任务里**已经批准**的一处契约变更：CR-005

`GET /api/auth/captcha` 与 `GET /api/auth/register-mode` 在契约里 `data` 是 `Record<string, any>`
（`ApiResponseMapStringObject`），**具体字段名没有静态声明**。前端为此做了一层「人工窄化 + 运行时校验」的临时层，
并在注释里写明"这是契约不足，正式做法是提 CR"。

**本任务批准把它修掉**（登记为 CR-005）：

- `captcha` → 具名返回类型（如 `CaptchaVO`，字段 `uuid`、`base64Image`）
- `register-mode` → 带取值约束的字段（`open` / `invite` / `closed`）

**范围严格限制**：只动 `auth` 包里与这两个接口的**返回类型**相关的部分。
**不要把这次变更扩大成 auth 模块重构** —— M1 已交付并复核，改动越大越可能碰坏它。
**做完必须跑一遍全量 `mvn test`**，确认 M1 的 23 项仍全绿。

> **为什么把它塞进这个任务**：M3 是本项目**第一次真正扩张契约**，契约变更流程要在这里走通一遍；
> 而这一条不修，前端的临时层就永远没有到期日（临时层不退场就会变成永久设施）。

---

## 8. 明确交到你头上的交接事项

| # | 事项 | 为什么不能省 |
|---|---|---|
| **H1** | 为 `2002 发帖过于频繁` 补**真实触发用例**（测试名 `M3_post_rate_limit_triggers_2002`） | M1 只做了契约完整性断言（枚举里有这个码），**没有真实触发路径**。**M3 的发帖接口就是触发点**；不补的话"发帖限流真的生效吗"永远没人验证过。频率阈值见 §8.7（新用户 24h ≤ 3 帖／普通 ≤ 10 帖每小时） |
| **H4** | 敏感词的**完整能力**（DFA／图片审核／审核队列／降级策略）**不归你** | M1 是朴素子串匹配，只够让 `2001` 有触发路径。你在发帖时**复用它**即可，别顺手重写 —— 那是 M5 的范围 |

> 顺带：`M3_post_rate_limit_triggers_2002` 这个名字**目前不在** `验收项-测试映射.md` 里（那份表由 L1 独占）。
> 你按这个名字实现，**并在交付报告里点出来**，由 L1 登记进映射表。

---

## 9. 契约不足或环境受阻时怎么办

- **契约不足（缺字段／缺接口／语义不明）**：**不改契约、不绕过** → 结束本轮并报告 `BLOCKED + CR 内容`。
- **OSS 环境没就绪**（环境变量缺、CORS 没配、隧道没通）：**报 `BLOCKED + 具体缺哪一项`**。
  **不要**为了"先跑通"自己造一个假签名、或把回调改成前端确认制 —— 后者要改 ADR-0007。
- **需要动 §2 之外的文件**：停下来报告，不要自己扩范围。

---

## 11. 交付分段（L1 已定，**按这个执行**）

OSS 的三项前置（环境变量 / CORS / 回调隧道）**目前尚未验证**，而且其中一项（安全组）只有需求方能做。
所以本任务**分段交付**，不要在 OSS 上卡住：

### 第一交付段 —— **只做这些，做完就停下报告**

- 版块：`GET /api/boards`
- 帖子：`GET /api/posts`、`GET /api/posts/{id}`、`POST /api/posts`、`PUT /api/posts/{id}`、`DELETE /api/posts/{id}`、`GET /api/posts/search`
- 网盘字段归一化（`diskUrl` / `diskCode`）+ 一键复制内容格式
- **CR-005**（`captcha` / `register-mode` 具名化，见 §7.3）
- §6.2 里**能脱离 OSS 凭据跑通**的那些测试（你自己判断哪些能、哪些不能，
  **在报告里逐条说明**：跑了 / 没跑 / 为什么）

**第一段结束时**：跑全量 `mvn test`（含 M1 那 23 项，必须仍全绿）+ `check_test_coverage_gaps.ps1`，
贴输出，报告"哪些验收项已覆盖、哪些还没有"，然后**停下等 L1 回话**。

### 第二交付段 —— **等 L1 通知再开工**

- `GET /api/oss/signature`、`POST /api/oss/callback`（验签）、图片落库
- `M3_publish_resource_post_with_images` 的**图片部分**、`M3_oss_callback_rejects_bad_signature`

**开工前先探测 §4 那三项**；缺任何一项 → 报 `BLOCKED + 具体缺哪一项`，
**不要自己造一个假签名或把回调改成前端确认制**（那要改 ADR-0007）。

> **为什么分段**：M3 的 9 个端点里只有 2 个碰 OSS，其余 7 个完全不依赖它。
> 让整块任务等一条只有需求方能加的安全组规则，是纯粹的浪费。
> 但**分段不等于降标准** —— 每一段各自的 DoD（红→绿输出、反向验证、覆盖缺口）一条都不少。

1. **先探测环境，再写代码**。OSS 那三项（环境变量 / CORS / 隧道）**任何一项没就绪，你的验收都跑不完** —— 提前 5 分钟探测，省掉后面一小时的误判。
2. **`ssh -R` 的坑**：OpenSSH 默认只把反向端口绑在远端 `127.0.0.1`，外网访问不到。现象是"隧道通了、ECS 上 curl 也通、但 OSS 回调超时"，**看起来像签名问题**。解法见 [`../../ops/OSS配置清单.md`](../../ops/OSS配置清单.md) §5。
3. **`@Valid` 校验发生在 Controller 方法体之前**（[`../../testing/README.md`](../../testing/README.md) §5.1 坑二）：测限流／越权／业务分支时，**必须用契约允许的合法请求体**，否则永远先命中 `400`，把真 bug 掩盖掉。
4. **测试类一律以 `Test` 结尾**（坑一）。
5. **回调验签是本任务最硬的一块**：OSS 用 RSA 公钥验签（`x-oss-pub-key-url` 头指向公钥地址）。**先把"伪造签名被拒"这条写出来跑红，再实现验签** —— 否则很容易写成"只要字段齐就落库"，而那是可被伪造的。
6. 建议**分段交付**并在报告里说清：先做 `boards`＋`posts` CRUD＋search（无图），再做 `media`（签名＋回调＋图片落库）。**任一段没完成就如实标注** —— 本项目的验收只认命令输出，不认"基本上做完了"。
7. 提交信息用 Conventional Commits，例如 `feat(post): 版块列表与帖子 CRUD`、`feat(media): OSS 签名与回调验签`。
