# VoiceBook BFF（server 模块）

App 与三个内容源之间的**唯一后端**。把 Artalk / Miniflux / calibre 的接入差异全部收敛在这里，
App 只需要认一个地址、带一个 `Authorization: Bearer <OIDC access_token>`。

```
App ──① OIDC PKCE──> Authelia (https://nas.wangyl.work:8461)
 │
 └──② Bearer <access_token>──> BFF ──┬─ Miniflux  管理员代管账号 + Basic Auth
                                     ├─ Artalk    sso/exchange 换 JWT
                                     ├─ calibre   OPDS + 共享只读账号
                                     └─ LLDAP     注册(GraphQL) / 改密(LDAP 协议)
```

本文档里所有"实测"结论都是在本机跑通后记录的真实结果，不是设计推测。

---

## 一、为什么需要它

| 问题 | BFF 的解法 |
|---|---|
| LLDAP 的 GraphQL **建得了号、设不了密码**（OPAQUE 零知识证明，密码只能走 LDAP 协议）| `/auth/register` 两步走：GraphQL 建号 → LDAP 设密码 |
| Authelia 签发的 `access_token` 是**不透明 token**（实测 99 字符，非 JWT，只有 `id_token` 是 JWT）| 用 `userinfo` 端点校验，加 60 秒缓存（Artalk 也是这么做的）|
| Miniflux 的 `AUTH_PROXY_HEADER` 只对 **Web 会话**生效，`/v1/…` API 只认 `X-Auth-Token` / Basic | 持管理员 token 代管用户：建号 + 设派生密码，之后用 Basic 调 API |
| calibre 只认自己的表单登录，没有 API token | 反代 OPDS，用本服务持有的**共享账号**（书库内容全站一致）|
| Artalk 要的是它自己的 JWT | 用用户的 OIDC token 调 `sso/exchange` 换取 |

---

## 二、API 一览

所有 `/api/**` 都需要 `Authorization: Bearer <OIDC access_token>`。

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/register` | 注册。body：`{username, email, password, displayName?}` |
| POST | `/auth/password` | 改自己的密码。body：`{username, oldPassword, newPassword}`（需登录）|
| GET | `/auth/me` | 当前用户（sub / username / email / groups）|

### 内容源代理

| App 侧路径 | 上游 | 鉴权注入 |
|---|---|---|
| `/api/miniflux/**` | Miniflux `/v1/**` | `Authorization: Basic <username>:<派生密码>` |
| `/api/artalk/**` | Artalk `/api/v2/**` | `Authorization: Bearer <Artalk JWT>` |
| `/api/calibre/**` | calibre `/**`（OPDS）| `Authorization: Basic <共享账号>` |
| `GET /healthz` | — | 健康检查（无需认证）|

举例：
```
GET  /api/miniflux/v1/entries?status=unread&limit=50
GET  /api/miniflux/v1/me
GET  /api/artalk/comments?page_key=/miniflux/entry/12345
POST /api/artalk/comments          {"page_key":"/miniflux/entry/12345","content":"..."}
GET  /api/calibre/opds/new?offset=0
GET  /api/calibre/opds/cover/42
```

---

## 三、构建

前置：JDK 21（本机可用 Android Studio 自带的）。

```bash
cd <仓库根>
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :server2:buildFatJar
# 产物：server2/build/libs/server-all.jar（约 38 MB）
```

---

## 四、部署到 NAS

### 1. 上传产物与编排文件

```bash
ssh nas 'mkdir -p /home/wangyl/work/service/voicebook-bff'

# 只传 jar + compose + env 模板（不用传源码；镜像里不编译）
scp server2/build/libs/server-all.jar nas:/home/wangyl/work/service/voicebook-bff/
scp server2/Dockerfile server2/docker-compose.yml server2/.env.example \
    nas:/home/wangyl/work/service/voicebook-bff/
```

### 2. 准备 `.env`

三个值都能从既有部署里拿到，不用手抄：

```bash
ssh nas
cd /home/wangyl/work/service/voicebook-bff

# LLDAP 管理员密码：直接取自 SSO 部署的 .env
grep '^LLDAP_ADMIN_PASS=' /home/wangyl/work/service/sso/.env

# Miniflux 管理员 API token：
#   方式一（推荐）在 Miniflux 页面「Settings → API Keys → Create a new API key」生成
#   方式二 直接取已有 token（api_keys 表里是明文）
#     ssh test "docker exec postgres psql -U miniflux -d miniflux -tAc \
#       \"SELECT token FROM api_keys WHERE description='Voicebook'\""

# 派生密钥（本地生成，不进对话）
openssl rand -hex 32
```

写入 `.env`（权限 600）：

```bash
cp .env.example .env
chmod 600 .env
vi .env
#   LLDAP_ADMIN_PASSWORD=...
#   CALIBRE_USERNAME=testuser
#   CALIBRE_PASSWORD=...            # LLDAP 里任一有效账号的密码（OPDS 走 LDAP bind）
#   MINIFLUX_ADMIN_TOKEN=...
#   MINIFLUX_PASSWORD_SECRET=...
```

> calibre 的 OPDS 只要求"一个有效账号"——所有账号看到的书库相同，个人阅读进度由 App 本地保存。
> 用 LLDAP 账号即可（CWA 已接 LDAP，密码实时 bind 校验），实测 `testuser` 访问
> `/opds/new` 返回 **HTTP 200**（匿名则 401）。

### 3. 构建镜像并启动

```bash
cd /home/wangyl/work/service/voicebook-bff
docker build -t voicebook/bff:1.0.0 .
docker compose up -d
docker compose ps
docker logs --tail 30 voicebook-bff     # 应看到 "VoiceBook BFF 启动：issuer=..."
```

### 4. nginx 反代（对外入口）

新增 `/etc/nginx/sites-enabled/voicebook-bff`（端口 8470，复用现成的 acme 证书）：

```nginx
server {
    listen 8470 ssl;
    server_name nas.wangyl.work;

    ssl_certificate     /etc/nginx/ssl/nas.wangyl.work.crt;
    ssl_certificate_key /etc/nginx/ssl/nas.wangyl.work.key;

    client_max_body_size 64m;   # 上传/下载书文件

    location / {
        proxy_pass http://127.0.0.1:8470;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $http_host;
        proxy_set_header X-Forwarded-Port  $server_port;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_read_timeout 120s;
    }
}
```

```bash
# 把上面的文件传到 NAS 的 /tmp 后：
ssh -t nas 'sudo cp /tmp/voicebook-bff.conf /etc/nginx/sites-enabled/voicebook-bff \
  && sudo nginx -t && sudo nginx -s reload'
```

验证：`curl https://nas.wangyl.work:8470/healthz` → `ok`

> 注意容器内的 `extra_hosts` 已把 `nas.wangyl.work` 指向 `192.168.100.10`，
> 这样它调 `https://nas.wangyl.work:8461/api/oidc/userinfo` 时不会绕到公网。

### 5. 收敛三个内容源的端口（可选但建议）

BFF 到位后，calibre(8852)、Artalk(8853)、Miniflux(test:8448) 就**不该再对外开**，
否则用户可以绕过 BFF 直达。把它们限制到内网即可（这一步涉及 nginx 改动，请自行确认后再动）。

---

## 五、Miniflux 侧：不需要改 compose

**这里和最初的设想不一样，值得记一笔**：原本打算用 Miniflux 的反代认证
（`AUTH_PROXY_HEADER` + `X-Forwarded-User`）实现免密，实测发现**它只对 Web 会话生效**：

```
level=WARN msg="[API] No Basic HTTP Authentication header sent with the request"
authentication_failed=true client_ip=172.22.0.1 request_uri=/v1/me   → 401
```

而 App 不加载 Miniflux 的网页，所以对 API 这条路走不通。于是改成 **BFF 持管理员 token 代管用户**：

1. 用户名首次出现时，BFF 用管理员 token 查 `/v1/users`；不存在就 `POST /v1/users` 建号；
2. 用 `PUT /v1/users/{id}` 把密码设为 `HMAC-SHA256(PASSWORD_SECRET, username)` 的前 32 位；
3. 之后用 `Basic <username>:<派生密码>` 调 API。

这样**每个用户仍是各自独立的 Miniflux 账号**（订阅、已读、书签互不干扰），
且 BFF 不需要保存任何密码表——密码随时可由密钥重新派生。

> 之前按反代方案注入的 `AUTH_PROXY_HEADER` / `AUTH_PROXY_USER_CREATION` /
> `TRUSTED_REVERSE_PROXY_NETWORKS` 三个变量**留着无害**（不影响 API），
> 也可以顺手删掉再 `docker compose up -d` 重建。

---

## 六、环境变量

| 变量 | 必需 | 说明 |
|---|---|---|
| `VB_PORT` | 否 | 默认 8080 |
| `VB_ISSUER` | ✅ | Authelia 对外地址，如 `https://nas.wangyl.work:8461` |
| `VB_USERINFO_URL` | 否 | 默认 `{issuer}/api/oidc/userinfo`（Authelia 的路径不在根下）|
| `VB_LLDAP_HTTP_URL` | ✅ | 如 `http://192.168.100.10:17170` |
| `VB_LLDAP_LDAP_URL` | ✅ | 如 `ldap://192.168.100.10:3890` |
| `VB_LLDAP_BASE_DN` | ✅ | 如 `dc=voicebook,dc=local` |
| `VB_LLDAP_ADMIN_DN` | ✅ | 如 `uid=admin,ou=people,dc=voicebook,dc=local` |
| `VB_LLDAP_ADMIN_PASSWORD` | ✅ | LLDAP 管理员密码 |
| `VB_LLDAP_DEFAULT_GROUPS` | 否 | 默认 `calibre_web`，逗号分隔 |
| `VB_MINIFLUX_URL` | ✅ | 如 `https://test.wangxy.us:8448` |
| `VB_MINIFLUX_ADMIN_TOKEN` | ✅ | 管理员 API token（代管用户用）|
| `VB_MINIFLUX_PASSWORD_SECRET` | ✅ | 派生用户密码的密钥 |
| `VB_ARTALK_URL` | ✅ | 如 `http://192.168.100.10:8853` |
| `VB_CALIBRE_URL` | ✅ | 如 `http://192.168.100.10:8852` |
| `VB_CALIBRE_USERNAME` | 否 | OPDS 账号（留空则不带 Basic）|
| `VB_CALIBRE_PASSWORD` | 否 | 同上 |

---

## 七、验证 checklist

```bash
# 1) 健康检查
curl -s https://nas.wangyl.work:8470/healthz          # → ok

# 2) 未带 token → 401
curl -s -o /dev/null -w '%{http_code}\n' https://nas.wangyl.work:8470/auth/me

# 3) 用真实 OIDC token 调 /auth/me
curl -s -H "Authorization: Bearer $TOKEN" https://nas.wangyl.work:8470/auth/me
#    → {"sub":"...","username":"...","email":"...","displayName":"...","groups":[]}

# 4) 注册（会自动在 LLDAP 建号 + 设密码 + 加入 calibre_web 组）
curl -s -X POST https://nas.wangyl.work:8470/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@voicebook.local","password":"alice12345"}'
#    → {"ok":true,"message":"注册成功"}

# 5) 新账号立即可登录（Authelia 授权码流程），然后三个通道都通：
curl -s -H "Authorization: Bearer $TOKEN" https://nas.wangyl.work:8470/api/miniflux/v1/me
#    → {"id":3,"username":"alice",...}       ← 首次访问自动建号
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  'https://nas.wangyl.work:8470/api/calibre/opds/new?offset=0'     # → 200
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://nas.wangyl.work:8470/api/artalk/comments?page_key=/x'   # → Artalk JSON

# 6) 改密码后，用新密码重新登录（验证全局生效）
curl -s -X POST https://nas.wangyl.work:8470/auth/password \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"alice","oldPassword":"alice12345","newPassword":"alice54321"}'
```

自动化脚本：`~/work/hermes/workspace/sso-deploy/test-bff.sh`（本机跑，覆盖以上全部）。

---

## 八、App 侧怎么接

App 只需两件事：

1. **拿 token**：OIDC 授权码 + PKCE，redirect_uri 用 `voicebook://oauth2/callback`
   （Authelia 侧 client `voicebook-app` 已配好，`consent_mode: implicit`）
2. **调接口**：`baseUrl = https://nas.wangyl.work:8470`，每个请求带
   `Authorization: Bearer <access_token>`；401 时用 refresh_token 静默刷新

> `offline_access`（refresh_token）在 Authelia 上第一次请求会弹一次同意页，
> 用户点过之后就不再问——这是 IdP 对**新 scope**的正常行为，不是配置错误。

对应到代码（后续 P4/P5 阶段）：

| 文件 | 改动 |
|---|---|
| `core/network/.../OidcClient.kt` | 新增：PKCE、授权 URL、code→token、refresh |
| `feature/auth/.../AuthController.kt` | 方法签名不变，实现换成 OIDC + 调 BFF |
| `feature/auth/.../AuthStore.kt` | 存 access/refresh token 与过期时间 |
| `core/network/.../di/NetworkModule.kt` | 加 Bearer 注入 + 401 自动刷新 |
| `core/network/.../rss/MinifluxApi.kt` | `baseUrl` 指向 BFF，去掉 `X-Auth-Token` |
| `core/network/.../reader/api/CalibreWebApi.kt` | `baseUrl` 指向 BFF，去掉 Basic Auth |
| `core/network/.../ArtalkApi.kt` | 新增：评论读写，走 `/api/artalk/**` |

---

## 九、实测记录（2026-09-29，本机直连真实 NAS/test 环境）

| 用例 | 结果 |
|---|---|
| `/healthz`、未带 token → 401 | ✅ |
| OIDC 授权码 + PKCE 换 token | ✅ 99 字符（不透明 token）|
| `/auth/me` 走 userinfo 校验 | ✅ 返回真实身份 |
| `/auth/register` 建号 | ✅ LLDAP 建号 + 加 `calibre_web` 组 + LDAP 设密 |
| 新账号 OIDC 登录 | ✅ 注册后立即可登录 |
| Miniflux 首次访问 | ✅ 自动建号（`users` 表新增该用户）|
| calibre OPDS | ✅ HTTP 200 |
| Artalk 代理 | ✅ `sso/exchange` 换到 JWT 并转发 |
| `/auth/password` 改密 | ✅ 新密码立即可登录，旧密码失效 |
| 测试账号清理 | ✅ LLDAP 与 Miniflux 均删除，环境回到原状 |

---

## 十、安全注意

- BFF 的 `127.0.0.1:8470` 只绑本机，对外一律经 nginx；
- 三个内容源的端口（8852/8853/8448）应收回内网，否则用户可绕过 BFF 直达；
- `VB_LLDAP_ADMIN_PASSWORD` 是**目录管理员**权限，只放在 NAS 的 `.env`（权限 600）；
- `VB_MINIFLUX_ADMIN_TOKEN` 是 Miniflux 管理员 token，同样只放 `.env`；
- `VB_MINIFLUX_PASSWORD_SECRET` 决定所有用户的 Miniflux 派生密码，泄露等于泄露全部用户凭据；
- 生产环境如需更强的隔离，把 LLDAP 管理员密码换成专用的只读/建号账号。

---

## 十一、开发时容易踩的坑

- **Kotlin 的块注释可以嵌套**：KDoc 里写 `` `/v1/*` `` 会打开一个嵌套注释，
  报 "Unclosed comment"。写路径时用 `/v1/…`。
- **Ktor 3 的 `bearer` 认证没有 `challenge` DSL**，`authenticate` 返回 null 时默认 401。
- **LLDAP 的 GraphQL**：`Group` 类型字段是 `displayName`（不是 `name`）；
  `addUserToGroup(userId: String!, groupId: Int!)` 返回 `Success{ok}`。
- **Miniflux 的 `PUT /v1/users/{id}`** 把 `username` 当必填字段，漏了会回
  `The username is mandatory.`；不要顺手传 `is_admin`，免得把管理员降权。
