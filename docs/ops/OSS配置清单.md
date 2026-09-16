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
      "Resource": ["acs:oss:*:*:你的bucket名/*"]
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


### 5.5 每次要开发 OSS 回调时，先起隧道

```powershell
# 另开一个窗口，开着别关
ssh -N -R 18080:127.0.0.1:8080 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 myserver
```

然后自查（**这条要能返回 404，就说明整条链是活的**）：

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST http://8.138.237.212:18091/api/oss/callback -H "Content-Type: application/json" -d "{}"
# 期望 404（M3 实现后应变成 400/403 —— 因为空 body 过不了验签）
```


---

## 6. 已经拿到的值（2026-09-15）

| # | 值 | 状态 |
|---|---|---|
| 1 | **Bucket 名**：`hy-forum-2026` | ✅ 已提供 |
| 2 | **地域 / Endpoint**：华北2（北京）→ `oss-cn-beijing.aliyuncs.com` | ✅ 已提供 |
| 3 | **回调公网地址**：`http://8.138.237.212/api/oss/callback` | ✅ **已打通并实测**（§5.4）—— 借已放行的 :80，**不需要动安全组** |
| — | **Bucket 的 CORS** | ✅ **已实测通过**（2026-09-15，Agent 独立验证）：预检返回 `Access-Control-Allow-Origin: *`、`Allow-Methods: GET,POST,PUT,DELETE,HEAD`、`Max-Age: 86400`；匿名列举对象返回 403（公共读不允许列对象，正确） |
| — | **RAM 子账号与最小授权策略** | ❌ **未验证**（§3）—— 要真上传一次才能证明；**不需要进控制台，见下面注** |
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
| RAM 授权（真上传一次） | | ⬜ **未做** —— 唯一能证明的办法是真的传一个文件（见 §6 注） | §3 |
| Bucket 名 / 地域 / 回调地址 | 2026-09-15 | ✅ `hy-forum-2026` / 华北2（北京）`oss-cn-beijing.aliyuncs.com` / `http://8.138.237.212/api/oss/callback` | §6 |

> ⚠️ **另一件要记住的**：§5 那条通道是**开发期的临时设施**。
> **M3 验收完就应停掉**（或至少在不再需要远程回调时停掉），不要长期挂着：
> `rm /etc/nginx/sites-enabled/hy-forum-oss-callback && nginx -s reload`。

---

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-15 | 首版。三条红线、Bucket、CORS、RAM 最小授权、AccessKey 存放与自查、**`ssh -R` 的 `GatewayPorts` 坑**、三步验证 |
