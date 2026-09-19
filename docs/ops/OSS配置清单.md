# OSS 配置清单（M3 前置）

> **给谁看**：需求方。这份文件里的操作要你在阿里云控制台与 ECS 上做，Agent 做不了（没有你的账号）。
> **为什么单独一份**：M3 的验收标准是「可从零发布一条**带图**资源帖」，而铁律 8 规定
> **文件上传必须走 OSS 服务端签名直传、禁止后端中转** —— 所以 OSS 不通，M3 跑不完。
>
> 依据：[`adr/0007`](../adr/0007-OSS服务端签名直传.md)、[`技术方案.md`](../技术方案.md) §6（第 778–783 行）、
> `AGENTS.md` 铁律 5 与铁律 8、[`M3-计划与前置.md`](../agents/M3-计划与前置.md) §4。
>
> 日期：2026-09-15

---

## 🔴 先说三条红线

| # | 红线 | 为什么 |
|---|---|---|
| **1** | **绝不要把 AccessKey ID / Secret 贴进聊天、写进任何 `.md`、提交进仓库** | 对话记录与 git 历史都会长期留存。**我自己也不需要看到它** —— 配置方式见 §4 |
| **2** | **绝不要用主账号 AccessKey** | 主账号密钥泄露 = 整个阿里云账号失守。用 RAM 子账号，且只授这一个 Bucket 的上传权限（R7「最小授权」） |
| **3** | **绝不把 AccessKey 放进前端** | APK / 小程序包都能被反编译。前端**只拿服务端签出来的签名**，永远不持有密钥（铁律 5、铁律 8） |

---

## 1. 建 Bucket

控制台 → 对象存储 OSS → Bucket 列表 → **创建 Bucket**。

| 项 | 填什么 | 为什么 |
|---|---|---|
| Bucket 名称 | 全局唯一，建议 `hy-forum-img-<你的后缀>` | 名字全局唯一，太普通的名字会被占用 |
| 地域 | 选**离你最近的**（如华东1-杭州） | 影响上传/访问速度 |
| 读写权限 | **公共读** | 帖子图片要能在 `<img src>` 里直接加载。**私有**就得给每张图签 URL，M3 不做那套，徒增复杂度 |
| 版本控制 | 关闭 | 用不上，开了反而多占存储 |
| 日志 / 归档 / 冷归档 | 全部关闭 | 用不上，且会产生额外计费项 |

> ⚠️ **关于「公共读」与 R6（OSS 流量费用超预期）**：公共读本身不产生费用，费用来自**流量**。
> 先把功能跑通；防盗链（Referer 白名单）与 CDN 属上线前的事，登记在 R6 里，**M3 不做**。
>
> 🔴 **但要记住这笔账**：Bucket 一旦公共读且**没配防盗链**，任何知道
> `hy-forum-2026.oss-cn-beijing.aliyuncs.com/图片路径` 的人都能把图挂到自己的站上，
> **外网下行流量算你的钱**。M3 阶段这是**有意的取舍**，不是疏忽 —— 上线前必须处理。

---

## 2. 配 CORS（**不做这一步，H5 上传必然失败**）

Bucket → **数据安全** → **跨域设置** → 创建规则。

| 字段 | 填什么 |
|---|---|
| 来源 (Origin) | `http://localhost:5173`<br>`http://127.0.0.1:5173`<br>（上线前再加你的域名，如 `https://你的域名`） |
| 允许 Methods | 勾 `POST`、`GET`、`HEAD` |
| 允许 Headers | `*` |
| 暴露 Headers | `ETag`、`x-oss-request-id` |
| 缓存时间 | `600` |

**为什么要两条 Origin**：浏览器把 `localhost` 和 `127.0.0.1` 当作**不同的源**。
只写一条时，换一种写法访问前端就会上传失败 —— 而报错通常只是 CORS 的笼统提示，很费时间。

**为什么只需 `POST`**：服务端签名直传用的是 **PostObject**（表单直传），不是 PUT。
（若后端实现改成 PUT 预签名，则要勾上 `PUT` —— 以 M3 任务书为准。）

---

## 3. 建 RAM 子账号 + 最小授权

控制台 → **访问控制 RAM** → 身份管理 → 用户 → **创建用户**。

1. 登录名称：`hy-forum-oss-uploader`
2. 访问方式：**只勾「OpenAPI 调用访问」**（不要勾控制台登录 —— 它不需要登录控制台）
3. 创建完成后，**立刻**记下 `AccessKey ID` 与 `AccessKey Secret`
   （Secret 只显示这一次；丢了就删掉重建一个，不要找"找回"）

然后 → **权限管理** → 权限策略 → **创建策略** → 脚本编辑，粘这段（把 `你的bucket名` 换掉）：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject"],
      "Resource": ["acs:oss:*:*:hy-forum-2026/*"]
    }
  ]
}
```

再回用户页，把这条策略**授予** `hy-forum-oss-uploader`。

> **为什么只给 `PutObject`**：这个子账号只需要"往这个 Bucket 的一个目录里放东西"。
> 不给 `GetObject`（图片是公共读，不需要它）、不给 `ListObjects`（能列举 = 能看见别人传了什么）、
> 不给 `DeleteObject`（删除走后台，不走上传方）、**不给任何其它 Bucket 的权限**。
> 这份密钥即使泄露，损失上限是"往一个目录里塞垃圾"。
>
> **PostObject（表单直传）由 `PutObject` 授权。** 若上传时报 `AccessDenied`，
> 先核对这条策略的 `Resource` 有没有写对（Bucket 名 / 结尾的 `/*` 都不能少），
> 再确认策略确实授予了用户。

---

### 3.1 🔴 2026-09-15 实测：这一步**没做对**（`ImplicitDeny`）

清单里唯一一项需要"进控制台做且要验证"的事就是这个。2026-09-15 由 Agent 独立验证，
用真实的 AccessKey 对 Bucket 发了一次带签名的 `PUT`，结果：

```xml
<Code>AccessDenied</Code>
<Message>You have no right to access this object because of bucket acl.</Message>
<AccessDeniedDetail>
  <PolicyType>ResourceGroupLevelIdentityBasedPolicy</PolicyType>
  <AuthPrincipalType>SubUser</AuthPrincipalType>
  <NoPermissionType>ImplicitDeny</NoPermissionType>
  <AuthAction>oss:PutObject</AuthAction>
</AccessDeniedDetail>
<EC>0003-00000001</EC>
```

**怎么读这个报错**（这是本清单最有用的一段）：

| 看到什么 | 含义 |
|---|---|
| **签名被接受**（没有 `SignatureDoesNotMatch` / `InvalidAccessKeyId`） | ✅ **AccessKey 本身是有效的** —— 别去怀疑密钥 |
| `<NoPermissionType>ImplicitDeny</NoPermissionType>` | ❌ **没有任何策略允许这个动作**。"隐式拒绝"= 没找到允许的语句 |
| `<AuthAction>oss:PutObject</AuthAction>` | 明确是这一步被拒 |
| `<Message>...because of bucket acl</Message>` | **误导性文案** —— 它说"bucket acl"，但 `ImplicitDeny` 说明真正原因是**策略没生效**，不是桶权限 |

**只剩两种可能，按概率排序**：

1. **策略创建了，但没"授予"给用户** —— RAM 里**创建策略**与**授予用户**是两步，
   第二步（用户 → 添加权限 → 选择该策略）最容易漏。
2. **策略里的 `Resource` 没换成真实 Bucket 名** —— 原文若留着占位符 `你的bucket名`，
   策略语法合法、也能创建成功，但**匹配不到任何对象**。

> **本清单的 §3 已把 Bucket 名直接写死成 `hy-forum-2026`**（不再留占位符）——
> 一个"需要手动替换"的占位符就是**一个会被静默漏掉的步骤**。

**修完之后怎么验证**（Agent 可直接跑，不需要再进控制台）：

```powershell
# 用真实的签名 PUT 打一次；200 = 通过
node .tmp\oss-probe.mjs
```

**在那之前不要派 M3 第二交付段** —— 签名与回调实现的正确性无法在"授权不通"的环境上验证，
会得到一堆指向错误方向的失败（这正是 `ImplicitDeny` 文案误导人的地方）。

### 3.2 第二次实测：**授权已绑定，但仍 `ImplicitDeny`**

2026-09-16 需求方在 RAM 里完成了绑定（截图证据：`hy-forum-oss@1618576048139844`
用户 → 新增授权 → 权限策略 `hy-forum-oss-upload` → 资源范围"账号级别" → 执行状态**已完成**）。
**Agent 立刻重测，结果一字未变**：仍是 `AccessDenied` + `ImplicitDeny` + `oss:PutObject`。

**所以"绑定"这一步是对的，剩下的唯一可疑点是「策略内容本身」。**

**首要怀疑：ARN 里的 Bucket 名带了**看着一样、其实不同**的字符。**

需求方最初提供 Bucket 名时写的是 `hy‑forum‑2026` —— 那几个连字符**看起来是全角的
`‑`（U+2011）而不是 ASCII 的 `-`**。若策略的 `Resource` 写成
`acs:oss:*:*:hy‑forum‑2026/*`（全角），那么：

- 策略**语法合法** → 能保存
- **能绑定到用户** → 控制台显示"已完成"
- **但永远匹配不到任何对象** → `ImplicitDeny`

这是"看着对、其实不匹配"的典型，**肉眼看字符串几乎分辨不出来**。
（真实的 Bucket 名是 ASCII，证据：请求返回的 `HostId` 是 `hy-forum-2026.oss-cn-beijing.aliyuncs.com`。）

**排查办法（比肉眼比对可靠）**：把策略内容**复制出来**，用 `python` 打印每个字符的码点：

```python
s = 'acs:oss:*:*:hy-forum-2026/*'   # 粘贴策略里那一行
print([hex(ord(c)) for c in s if not c.isalnum()])
# ASCII 连字符是 0x2d；U+2011 / U+2010 / U+2013 都是"长得像"的冒牌货
```

### 3.3 第三次实测：**二分定位成功 —— 就是 ARN 串的问题**

2026-09-16，按"只改一个字符串"的思路做二分：把 `Resource` 从
`acs:oss:*:*:hy-forum-2026/*` 换成 **`acs:oss:*:*:*`**（放弃限定到具体 Bucket），
其余一字不动，**立刻重测**：

| Resource | 结果 |
|---|---|
| `acs:oss:*:*:hy-forum-2026/*` | ❌ 403 `ImplicitDeny` |
| `acs:oss:*:*:*` | ✅ **HTTP 200** |

**结论**：管道全部正常 —— AccessKey 有效、密钥属于被授权的那个用户、策略已正确绑定。
问题**只在那个 ARN 字符串本身**：`acs:oss:*:*:hy-forum-2026/*` 没有匹配到真实对象。
既然换掉它就好，说明串里**有一个字符不是它看起来的样子**（首要怀疑仍是全角连字符
`‑` U+2011 —— 需求方最初提供的 Bucket 名就写成了全角）。

> **这条二分法的价值**：一次"只改一个变量"的试验，就把
> 「策略内容问题」与「主体/绑定问题」彻底分开 —— 而在此之前我已经误判过一次
> （以为是"忘了授权"，而实际授权是对的）。**改一个变量、再测一次，比继续推理便宜得多。**

**遗留（需收回）**：当前策略是 `acs:oss:*:*:*`，即**账号内所有 Bucket 的 PutObject**，
比 R7（最小授权）要求的宽。**必须收回到只限 `hy-forum-2026`** —— 见 §3.4。

### 3.4 ✅ 已完成：Resource 已收回最小授权（2026-09-16）

**结果**：用 Agent 提供的那一行（ASCII 连字符）替换后，重测**仍是 `HTTP 200`**
→ **最小授权达成**：该子账号现在只能写 `acs:oss:*:*:hy-forum-2026/*`，
不再能写账号内其它 Bucket。

> 这一步同时**反证了 §3.3 的诊断**：同一个字符串、只换字符（ASCII `-` vs 全角 `‑`），
> 就从 403 变 200 —— 所以「ARN 里有一个长得像 ASCII 连字符的字符」是**被证明的结论**，
> 不是推测。**这也是本清单唯一一次"用一次修改验证一个诊断"的完整闭环。**

#### 原待办记录（保留 —— "该做什么"本身就是有用的信息）

**做法**：把 `Resource` 改成**下面这一行**，**复制粘贴、不要手打**
（手打正是引入全角字符的来源）：

```
"Resource": "acs:oss:*:*:hy-forum-2026/*"
```

Agent 端会做两件事验证：
1. 用 Python 打印该串的码点，确认连字符是 `0x2d`
2. 重跑 `node .tmp/oss-probe.mjs`，期望仍是 200

**若填回后再次 403**，则说明"粘贴进来的字符串也被污染"（输入法/剪贴板问题），
此时**接受 `acs:oss:*:*:*` 作为开发期的已知偏差**并在此登记，不再纠缠 ——
但要记下：**账号里若将来增加其它 Bucket，这个子账号也能写它们。**

**测试对象遗留**：`_connection-test/probe.txt`（几十字节）已写入 Bucket。
当前策略没给 `DeleteObject`，故 Agent 删不掉 —— 请在控制台手动删除该对象
（控制台用的是主账号，不需要额外授权）。

### 3.5 一条有用的技术：不用翻控制台就能核对"密钥属于哪个主体"

排查 §3.2 时我曾怀疑"密钥不属于被授权的那个用户"，于是让需求方去翻
RAM 用户的 AccessKey 列表。**那个办法不可靠** —— 需求方翻到的是
「**通行密钥**」区块（Passkey，FIDO 登录凭证），它和 AccessKey 是**两个不同的东西**，
于是得到了错误的"没有"。

**可靠办法**：OSS 的 `AccessDenied` 响应里带 `AuthPrincipalDisplayName`，
**它就是这次请求所使用凭据的主体 ID**。拿它与 RAM 控制台上该用户的「用户 ID」
直接比对即可，**不需要翻任何列表**：

```xml
<AuthPrincipalDisplayName>208112389532020531</AuthPrincipalDisplayName>
<!-- = RAM 用户 hy-forum-oss 的用户 ID（控制台・基本信息的「用户 ID」栏） -->
<!-- 一致 → 密钥属于该用户；不一致 → 密钥属于别的用户 -->
```

**教训**：让非专业用户去界面上"找并比对两个字符串"是很脆弱的一步 ——
他会点进名字相近但完全不同的功能区（通行密钥 vs AccessKey）。
**能由 Agent 从响应里读出来的证据，不要让人类去界面里找。**




---

## 4. AccessKey 放哪里（**这是唯一涉及密钥的一步**）


**规则：只放在你本机的环境变量里，或者一个被 git 忽略的文件里。**

两种方式，任选其一（后端 M3 会同时支持，任务书里会写明）：

**方式一：环境变量（推荐，进程级，最不容易误提交）**

起后端前，在**同一个 PowerShell 窗口**里设：

```powershell
# 注意：这是示例名，最终变量名以 M3 任务书/application.yml 为准
$env:OSS_ACCESS_KEY_ID     = '你的 AccessKey ID'
$env:OSS_ACCESS_KEY_SECRET = '你的 AccessKey Secret'
$env:OSS_BUCKET            = '你的bucket名'
$env:OSS_ENDPOINT          = 'oss-cn-hangzhou.aliyuncs.com'   # 按你选的地域改
```

**方式二：`web/.env.local` 那种被忽略的文件**

如果你更想写在文件里，**只能写进被 `.gitignore` 忽略的文件**：

- 前端的本地覆盖文件是 `web/.env.local` → **已被忽略**（`web/.gitignore` 的 `.env.local` 规则）
- 后端的本地配置是 `server/src/main/resources/application-local.yml` → **已被忽略**（根 `.gitignore` 有这条）

写完务必自查一次，**这一步不能靠"我记得"**：

```powershell
cd D:\demo\Hy论坛
git check-ignore -v web/.env.local
# 期望：打印出匹配的忽略规则（含 .gitignore 与行号）
# 若什么都不打印，说明它没被忽略 → 停下来，先别写密钥
```

---

## 5. 回调地址：**已于 2026-09-15 实际搭好**（结论：走「方案 B：不改 sshd」）

### 5.1 为什么不能直接 `ssh -R` 暴露出去

先实测了 ECS 的 sshd：

```
gatewayports no          ← 默认值，没有显式配置
allowtcpforwarding yes
```

**`GatewayPorts no` 意味着 `ssh -R` 只会把端口绑在 ECS 的 `127.0.0.1` 上，外网访问不到。**
（这与本节原稿预测的坑一致。）

**同时还查了 ECS 上原本在跑什么**（本项目吃过一次亏：D6 停 VM 容器弄断了需求方另一个项目）：

| 发现 | 影响 |
|---|---|
| `nginx 1.18.0` 已在 **`0.0.0.0:80`** 上跑着**别的站** | **不能碰 80**，也不能 restart nginx（用 `reload`） |
| 无 docker、`ufw` 规则未启用（`inactive`） | 少一道门 |
| 磁盘 34 GB 空闲、内存 1.6 GB（用 246 MB） | 充裕 |

### 5.2 实际方案：反向隧道 + nginx 只放行**一个路径**

```
前端直传 OSS ──成功──> OSS ──POST 回调──> 8.138.237.212:18091
                                              │  (nginx，只放行 POST /api/oss/callback)
                                              ▼
                                        ECS 127.0.0.1:18080
                                              │  (ssh -R 反向隧道)
                                              ▼
                                   你本机 127.0.0.1:8080  ← 开发中的后端
```

**为什么不整站转发**：整站转发 = 把**开发中的后端整个暴露到公网**（含 `/api/admin/login`
以及所有未完成、未加固的接口）。这不是可接受的取舍。

**部署了什么**：

| 项 | 值 |
|---|---|
| 隧道命令 | `ssh -N -R 18080:127.0.0.1:8080 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 myserver` |
| ECS 上的 nginx 配置 | `/etc/nginx/sites-available/hy-forum-oss-callback`（已软链到 `sites-enabled`） |
| 放行路径 | **仅 `POST /api/oss/callback`**；`GET` 同一路径 → 403；其余一切 → **444（直接断连，不回应）** |
| 监听端口 | `0.0.0.0:18091`（:80 上原有的站未受影响） |
| 停用方式 | `rm /etc/nginx/sites-enabled/hy-forum-oss-callback && nginx -s reload` |

### 5.3 实测验证（2026-09-15，全部有输出）

| 探测 | 结果 | 说明 |
|---|---|---|
| ECS 上 `ss -ltn \| grep 18080` | `127.0.0.1:18080` | 隧道**只绑 loopback**，符合预期 |
| ECS 上 `curl 127.0.0.1:18080/v3/api-docs` | **HTTP 200 / 0.026s** | ECS → 隧道 → 本机后端 **链路通** |
| ECS 上 `curl -X POST 127.0.0.1:18091/api/oss/callback` | **HTTP 404** | 穿透到 Spring 了（M3 还没实现该接口）→ **nginx → 隧道 → 后端 全线打通** |
| ECS 上 `curl 127.0.0.1:18091/v3/api-docs` | **HTTP 000（无响应）** | 444 白名单生效，未放行路径被掐断 |
| ECS 上 `curl -X GET 127.0.0.1:18091/api/oss/callback` | **HTTP 403** | 只允许 POST，生效 |
| **本机** `curl -X POST http://8.138.237.212:18091/...` | **HTTP 000** | ❌ **安全组还没放行** |
| **对照组** 本机 `curl http://8.138.237.212/` | **HTTP 200** | 出网正常、ECS 可达 → **差别就在安全组那一条规则** |

### 5.4 ✅ 不需要动安全组 —— 改借已经放行的 :80

**2026-09-15 定案。** 起因：需求方让我"看有没有什么可以打得通的"，而不是默认"这一步只能他自己做"。
探了三路，结论如下：

| 路 | 结果 |
|---|---|
| ECS 上能不能改安全组（aliyun CLI / 配置 / 环境凭据 / 实例 RAM 角色） | ❌ **都没有** → Agent 改不了安全组 |
| **:80 是不是已经开着** | ✅ **是**（`GET http://8.138.237.212/` → 200），而 :80 上跑的是一台 `listen 80 default_server` 的静态站（`/var/www/personal`，Nuxt 产物） |
| 能不能不改 sshd 就借道 | ✅ 能：给那个 server 块加**一行 `include`** |

**为什么借 :80 比开 18091 更好**：不需要任何控制台操作；URL 稳定（80 端口不会被安全组策略变动影响）；
而且它暴露面**更小** —— 18091 是一个新增的公网监听端口，借道则**一个端口都没新增**。

**做法（可核对、可一行撤销）**：

- 逻辑放在 `/etc/nginx/snippets/hy-forum-oss-callback.conf`（**Agent 自己的文件**）
- 既有站点 `/etc/nginx/sites-available/personal` 只加**一行** `include`，写在 server 块收尾 `}` 之前
  （插入前先 `cp -a` 备份为 `personal.bak-<时间戳>`，脚本输出 `diff -u` 供核对 —— **实测差异只有那 5 行**）
- **用 `location = /api/oss/callback` 精确匹配**：nginx 的匹配优先级是 `=` 精确 > `^~` > 正则 > 前缀，
  所以**其余请求一字不变地走 personal 站点自己的 `location /`**（已验证：公网 `GET /` 仍是 200）
- 用 `nginx -s reload` 而非 `restart`，不打断 :80 上已有的连接
- 同时**删掉了原先 18091 的独立站点**（减少一个公网监听端口；`sites-enabled` 里现在只有 `personal`）

**撤销方式**（三选一）：

```
① 注释掉 personal 里那行 include → nginx -s reload        ← 推荐（include 指向不存在的文件会让 nginx 起不来）
② 停掉 ssh -R 隧道进程即可：回调变 502，公网不再触达你的开发机
③ rm /etc/nginx/snippets/hy-forum-oss-callback.conf + 同时做 ①
```

> ⚠️ **一个要记住的脆弱点**：`personal` 是**手工部署的静态站**。
> 如果将来有人重新部署它、或者覆盖了那份 nginx 配置，**那行 `include` 会消失**，
> 回调就变成 404 或落到静态站的 `try_files` 上 —— 而现象会是"OSS 上传成功但图片不落库"，
> **看起来像后端 bug**。排查时先看这行 include 在不在。

### 5.5 每次要开发 OSS 回调时，先起隧道

```powershell
# 另开一个窗口，开着别关
ssh -N -R 18080:127.0.0.1:8080 -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=6 -o TCPKeepAlive=yes myserver
```

自查（三条都要对，缺一条就说明某一环断了）：

```powershell
# ① 隧道通不通（期望 200）
curl.exe -s -o NUL -w "%{http_code}`n" http://8.138.237.212/hy-forum-tunnel-health
# ② 回调入口通不通（M3 实现前期望 404；实现后空 body 过不了验签，期望 400/403）
curl.exe -s -o NUL -w "%{http_code}`n" -X POST http://8.138.237.212/api/oss/callback -H "Content-Type: application/json" -d "{}"
# ③ 只允许 POST（期望 403）
curl.exe -s -o NUL -w "%{http_code}`n" http://8.138.237.212/api/oss/callback
```


### 5.6 ✅ 2026-09-16 复验 + 三个会让人白忙一场的坑

**复验（L1 实跑，全部有输出）**：本条链路的**每一环都还活着** ——

```powershell
# ① 隧道（公网 → ECS:80 → ssh -R → 本机 8080）。期望 200，且返回的路径数应等于当前契约
curl.exe -s -o .tmp\tunnel-health.json http://8.138.237.212/hy-forum-tunnel-health
python -c "import json,io;d=json.load(io.open(r'.tmp/tunnel-health.json',encoding='utf-8-sig'));print('路径数 =',len(d['paths']))"
# 实测：HTTP 200，路径数 = 12（= M3 第一交付段重导后的契约）→ 隧道 + nginx + 本机后端三者都通

# ② 回调入口（POST）。M3 第二交付段**尚未实现**该端点，所以期望**我们自己后端的统一 404**
curl.exe -s -X POST http://8.138.237.212/api/oss/callback
# 实测：{"code":404,"message":"资源不存在"} ← 注意这是**我们后端**吐的，不是 nginx 的页面
#       → 证明 OSS 的请求真的到达了开发机（第二段实现后这里应变成验签失败/成功的业务响应）

# ③ 同路径用 GET。期望 **403**
curl.exe -s -o NUL -w "%{http_code}`n" http://8.138.237.212/api/oss/callback
```

> ②③ 的判据是**响应体**而不是状态码：**我们自己后端的 JSON**（`{"code":…,"message":…}`）
> 与 **nginx/静态站的 HTML 错误页** 是两种东西。只看状态码会分不清"到了后端"还是"被 nginx 挡了"。

#### 坑 1：`GET /api/oss/callback` 返回 **403 是设计如此**，不是坏了

见 §5.2 那个 snippet：`limit_except POST { deny all; }` —— **GET 等一律 403**，避免这个路径被当普通页面探测。
**用浏览器打开它会看到 `403 Forbidden`（nginx 的页面）**，那是**正确行为**。别据此判断隧道断了。

#### 坑 2：**不要在已有隧道时再起一个**

`ssh -R` 带 `ExitOnForwardFailure=yes` 时，一旦远端 18080 已被占用，它会立刻退出：

```
Error: remote port forwarding failed for listen port 18080
tunnel exited (code 255); reconnecting in 5s      ← supervisor 会一直重试、一直失败
```

**2026-09-16 就发生过**：上一个会话留下的隧道 ssh 还活着（它由一个仍存活的 supervisor 管着），
于是新起的 supervisor 永远绑不上端口。
**先查有没有人占着**，再决定要不要起：

```powershell
# 本机：有没有既有的隧道进程
Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" | Select-Object ProcessId,ParentProcessId,CommandLine
# ECS：18080 的监听者是谁（是既有隧道的 sshd，还是别的东西）
ssh myserver "ss -ltnp | grep 18080"
```

已经在跑就**复用它**（它转发的就是本机 8080，换后端不用重起）。真要重起：

```powershell
# 推荐用带自愈的 supervisor（断了会自动重连），而不是裸 ssh
powershell -NoProfile -File scripts\start_oss_callback_tunnel.ps1
```

#### 坑 3：隧道健康检查返回的 `servers[0].url` **不是你仓库里的那个值**

它带着**请求进来的 Host**（走公网时是 `http://8.138.237.212`，直连本机时是 `http://127.0.0.1:8080`）。
这正是 CI 漂移检查曾经**假红**的根因（`servers[0].url` 里带了监听端口/Host）——
所以两处比对都**两侧摘掉 `servers`** 再比。**端口与 Host 不属于契约。**

#### 坑 4：**"僵尸监听者"** —— 表现为 **504**，不是连接拒绝（2026-09-17 实遇）

**现象**：`GET /hy-forum-tunnel-health` → **HTTP 504**；同时 OSS 回调报
`CallbackFailed: Error status : -1. OSS can not connect to your callbackUrl`（EC `0007-00000203`）。

**两层真相同时存在**（所以只修一条没用）：

| 层 | 状况 |
|---|---|
| 本机 | **隧道 ssh 进程数 = 0** —— 隧道根本没起（supervisor 起过但没绑上端口，静默没成功） |
| ECS | **18080 仍被一个 sshd 占着** —— ssh -R 的**客户端早就死了，sshd 还守着端口**；nginx 连上去**没人转发** → 超时 → **504** |

**为什么难查**：`504 Gateway Time-out` 看起来像"后端挂了/后端慢"，真相却是"远端有个**僵尸**占着端口"。

**判据（记住这一条就够）**：
- **连接被拒绝（refused）** → 端口上**没有监听者** → 隧道没起；
- **504** → 端口上**有监听者但不转发** → 十有八九是**僵尸**。

**处置（顺序不能反）**：

```powershell
@(Get-CimInstance Win32_Process -Filter "Name='ssh.exe'").Count   # ① 本机有没有隧道（0 = 没起）
ssh myserver "ss -ltnp | grep 18080"                              # ② ECS 上 18080 的占用者是谁
ssh myserver "kill <pid>"                                         # ③ 杀孤儿（否则新隧道永远绑不上）
powershell -NoProfile -File scripts\start_oss_callback_tunnel.ps1 # ④ 起隧道
curl.exe -s -o NUL -w "%{http_code}`n" http://8.138.237.212/hy-forum-tunnel-health   # ⑤ 期望 200
```

**与坑 2 的区别**：坑 2 是"**有活的隧道**时别起第二个"（报 `remote port forwarding failed`）；
坑 4 是"**端口被不工作的监听者占着**"（报 504）。**两种都表现为"隧道起不来"，但一个是重叠、一个是残留。**

**同日一条更重要的教训**：这次故障的第一现场是**前端最小闭环用例红了一条**
（`OSS 直传应返回 200，实际 203`）。第一反应容易是"前端的用例挂了" ——
**而真因是 L1 起后端时漏设了 `OSS_CALLBACK_URL`**（后端那条环回 WARN 三行日志就把它指出来了）。
**看到别人红，先查自己给的环境。**


---

## 6. 已经拿到的值（2026-09-15）

| # | 值 | 状态 |
|---|---|---|
| 1 | **Bucket 名**：`hy-forum-2026` | ✅ 已提供 |
| 2 | **地域 / Endpoint**：华北2（北京）→ `oss-cn-beijing.aliyuncs.com` | ✅ 已提供 |
| 3 | **回调公网地址**：`http://8.138.237.212/api/oss/callback` | ✅ **已打通并实测**（§5.4）—— 借已放行的 :80，**不需要动安全组** |
| — | **Bucket 的 CORS** | ✅ **已实测通过**（2026-09-15，Agent 独立验证）：预检返回 `Access-Control-Allow-Origin: *`、`Allow-Methods: GET,POST,PUT,DELETE,HEAD`、`Max-Age: 86400`；匿名列举对象返回 403（公共读不允许列对象，正确） |
| — | **RAM 子账号与最小授权策略** | ✅ **已验证**（2026-09-16，带签名 PUT → **HTTP 200**），且 Resource 已收回为**仅 `hy-forum-2026`**（§3.4）；匿名 PUT 仍被拒 403 |
| — | **AccessKey 是否已放进环境变量／忽略文件** | ❌ **未验证**（§4，且**不要发给我**） |
| — | ~~安全组放行 18091/TCP~~ | ✅ **不再需要** —— 改借 :80 之后这一步作废（§5.4） |

> ⚠️ **「OSS 已开通」不等于「配置可用」。** 上表还剩两项没验，但**都不需要进控制台**：
> - **CORS**：已由 Agent 独立测过（上表第 2 行）。**验证方法不一定要浏览器** ——
>   `curl -X OPTIONS -H "Origin: ..." -H "Access-Control-Request-Method: POST"` 能直接看到响应头。
> - **RAM 授权**：只有一个办法能证明 —— **真的传一个文件上去**。做法：把 AccessKey 放进
>   `web/.env.local`（被 gitignore），然后让 Agent 跑一次签名上传测试
>   （它**从文件读、不打印值**，所以密钥不会进入任何对话记录）。**传完记得删掉测试对象**。

---

## 7. 怎么确认配对了（三步，全部要有输出）

**第 1 步：CORS 生效** —— 用浏览器，不要用 curl（curl 不做预检，测不出 CORS）

打开 Chrome，F12 切到 Console，粘这段（把 Bucket 与地域换掉）：

```js
fetch('https://你的bucket名.oss-cn-hangzhou.aliyuncs.com/', { method: 'HEAD' })
  .then(r => console.log('OK', r.status))
  .catch(e => console.log('FAIL', e))
```

期望：**`OK 200`**（或 403 —— 那是权限问题，不是 CORS）。
若报 `blocked by CORS policy`，说明 §2 没配对。

**第 2 步：RAM 子账号的密钥可用**（在**本机**，不要把密钥写进命令历史太久）

```powershell
# 这一步只是确认"这对密钥存在且能签名"，不是真上传
# 具体命令由 M3 任务书给出；现在只要能确认 §4 的环境变量已设好：
if ($env:OSS_ACCESS_KEY_ID) { "AccessKey 已设置（长度 $($env:OSS_ACCESS_KEY_ID.Length)）" } else { "未设置" }
```

**第 3 步：隧道可达**（**这条最容易漏，也最容易误判**）

```powershell
# 在隧道开着的窗口之外，另开一个窗口
curl.exe -s -o NUL -w "%{http_code}`n" http://8.138.237.212:18080/v3/api-docs
```

期望：**`200`**。若超时，回到 §5 的两个解法逐个试。

> **这三步的意义**：M3 一旦开工，实现者会用这三条来判断"是不是 OSS 没配好"。
> **先把它们验完，能省掉 M3 期间最费时间的一类误判。**

---

## 8. 验收记录

| 步骤 | 日期 | 结果 | 证据 |
|---|---|---|---|
| ECS 侦察（sshd / 原有服务 / 端口 / 磁盘） | 2026-09-15 | ✅ `GatewayPorts no`；nginx 1.18 占着 :80（未碰）；18080/18091 空闲；磁盘 34 GB | §5.1 |
| 隧道建立 | 2026-09-15 | ✅ ECS 上绑 `127.0.0.1:18080`；`curl 127.0.0.1:18080/v3/api-docs` → **HTTP 200 / 26ms** | §5.3 |
| nginx 白名单部署 | 2026-09-15 | ✅ `nginx -t` 通过、`reload`（非 restart）后监听 `0.0.0.0:18091`，:80 上的老站不受影响 | §5.3 |
| 白名单生效性 | 2026-09-15 | ✅ 放行路径穿透到 Spring（404）；未放行路径 444（HTTP 000）；GET 同路径 403 | §5.3 |
| **公网可达** | 2026-09-15 | ✅ **已通过**（改借 :80 之后）：`POST http://8.138.237.212/api/oss/callback` → **404**（Spring 给的，M3 未实现）= 全线打通；`GET /hy-forum-tunnel-health` → **200**；`GET /` → **200**（personal 站未受影响）；`GET /api/oss/callback` → **403**（只允许 POST） | §5.4 |
| 安全组放行 18091/TCP | 2026-09-15 | ✅ **已作废** —— 改借已放行的 :80，一个端口都没新增 | §5.4 |
| CORS（公网预检响应头） | 2026-09-15 | ✅ `Allow-Origin: *`、`Allow-Methods: GET,POST,PUT,DELETE,HEAD`、`Max-Age: 86400`；匿名列举 → 403 | §6 |
| RAM 授权（真上传一次） | 2026-09-16 | ✅ **HTTP 200**（最小授权版 `acs:oss:*:*:hy-forum-2026/*`）；匿名 PUT 仍 403 | §3.4 |
| Bucket 名 / 地域 / 回调地址 | 2026-09-15 | ✅ `hy-forum-2026` / 华北2（北京）`oss-cn-beijing.aliyuncs.com` / `http://8.138.237.212/api/oss/callback` | §6 |

> ⚠️ **另一件要记住的**：§5 那条通道是**开发期的临时设施**。
> **M3 验收完就应停掉**（或至少在不再需要远程回调时停掉），不要长期挂着。
>
> **怎么停（2026-09-16 更正 —— 原来的写法指错了文件，照着做不会有任何效果，而你会以为已经撤掉了）**：
> 真正的机制是 `personal` 站点里的一行 `include`，指向
> `/etc/nginx/snippets/hy-forum-oss-callback.conf`（**没有** `sites-enabled/hy-forum-oss-callback` 这个文件）。
> 三种撤销方式（任选其一，**改完都要 `nginx -s reload`**）：
> ① **推荐**：把 `personal` 里那行 `include` 注释掉（**不要**直接 `rm` 那个 snippet ——
>    `include` 指向不存在的文件会让 **nginx 启动失败**，连带把机器上**别人的静态站**一起弄挂）；
> ② `rm /etc/nginx/snippets/hy-forum-oss-callback.conf` **且**注释掉那行 `include`；
> ③ **最省事**：停掉隧道（`ssh -R` 那个进程）—— 回调立刻变成 502，公网不再触达开发机，
>    而 nginx 配置一个字都不用动。
>
> **验收口径**：撤掉后 `GET http://8.138.237.212/hy-forum-tunnel-health` 应不再返回我们的 `api-docs`。

---

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.1 | 2026-09-16 | **§5.6 复验记录 + 三个坑**（L1 实跑）：① **整条链路今天仍全部活着** —— `GET /hy-forum-tunnel-health` → 200 且返回**12 路径**（= 重导后的新契约），`POST /api/oss/callback` → **我们后端自己的统一 404**（端点尚未实现，但**证明了 OSS 的请求真的到达开发机**）。② 记下三个会让人白忙一场的坑：**`GET` 该路径返回 403 是设计如此**（`limit_except POST`，不是隧道断了）；**已有隧道时不要再起一个**（`ExitOnForwardFailure=yes` 会一直报 `remote port forwarding failed for listen port 18080` —— 今天就发生过，上个会话的隧道 ssh 还活着）；**健康检查返回的 `servers[0].url` 带着请求进来的 Host**，那正是 CI 漂移检查曾假红的根因。③ **修掉本节两处会误导人的地方**：删掉**重复且过期**的那份 §5.5（它写的是被放弃的 `:18091`，照着做会打到没放行的端口）；更正"怎么停掉这条通道"的命令 —— 原文让人 `rm sites-enabled/hy-forum-oss-callback`，**那个文件根本不存在**，照着做不会有任何效果而你**会以为已经撤掉了**（真实机制是 `personal` 里一行 `include`，且**直接删 snippet 会让 nginx 启动失败、连带弄挂机器上别人的静态站**） |
| v1.0 | 2026-09-15 | 首版。三条红线、Bucket、CORS、RAM 最小授权、AccessKey 存放与自查、**`ssh -R` 的 `GatewayPorts` 坑**、三步验证 |
