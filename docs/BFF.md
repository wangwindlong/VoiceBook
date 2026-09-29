# VoiceBook BFF（统一网关）部署、开发与维护

BFF 是 App 唯一的后端入口。App 用 Authelia 的 OIDC access_token 调用 BFF，BFF 再替用户访问 LLDAP、Miniflux、Artalk、calibre（CWA）。三个组件不再对外暴露。

对应设计稿《VoiceBook × 统一认证：App 对接设计》的 P1–P3。与设计稿不同的地方（均经实测确认）：

| 设计稿 | 实际实现 | 原因 |
|---|---|---|
| JWKS 本地验签 | 调 Authelia `/api/oidc/userinfo`，按 token 哈希缓存 60 秒 | Authelia 的 access_token 是 99 字符的不透明 token，不是 JWT |
| Miniflux 反代 + `X-Forwarded-User` | 管理员 API key 建号 / 设密，BFF 以 Basic 认证代用户调 `/v1` | `AUTH_PROXY_HEADER` 只对 Web 会话生效，`/v1` API 只认 Basic / X-Auth-Token |
| BFF 路径 `/auth/*` | 全部在 `/api/` 下，且 BFF 使用独立的 nginx server 块 | Authelia 自己占用 `/api/oidc/*`，同一 host:port 下会冲突 |
| calibre 账号靠 JIT | 注册 / 改密时 BFF 代用户登录一次 CWA；另有 `/api/calibre/activate` 兜底 | CWA 只在 LDAP 表单登录时才在 app.db 建用户行，没有这行就无法记进度 |
| App 用系统浏览器走 PKCE | App 原生表单提交账号密码给 `POST /api/auth/login`，BFF 代为完成 Authelia 登录 + PKCE 授权 + 换 token | 四端无需处理浏览器回调；Authelia 没有密码模式，BFF 扮演浏览器（first factor → 读授权重定向里的 code，不跟随 → 换 token → 注销临时网页会话） |

## 1. 代码结构

```
core/api-contract/   KMP 模块：BFF 与 App 共用的 DTO（BffModels.kt）和路径常量（BffRoutes.kt）
server/              JVM 模块，包名 us.wangxy.voicebook.server.bff
  Application.kt       入口、插件（日志/JSON/错误/限流/认证）、路由挂载
  BffServices.kt       对象装配（测试里用假实现替换）
  config/BffConfig.kt  全部配置，来自环境变量（支持 X_FILE 形式读 docker secret）
  auth/                OIDC token 校验（userinfo + 缓存）
  account/             注册、改密、各组件开通（Provisioner）
  lldap/               GraphQL 管账号/分组，LDAP 协议设密码（RFC 3062）
  miniflux/            管理员代管用户 + 按用户透传 /v1
  artalk/              sso/exchange 换 JWT（按用户缓存）+ 评论
  calibre/             metadata.db 只读、app.db 进度读写、CWA 代登录
  routes/              HTTP 路由
server/deploy/       Dockerfile、docker-compose.yml、.env.example、nginx/Authelia 配置片段、remote-up.sh
scripts/             deploy-bff.ps1 / deploy-bff.sh（一键部署）、bff-token.ps1（取 token）、bff-smoke.ps1（验证）
```

## 2. API

除 `/healthz`、`config`、`login`、`refresh`、`logout` 与注册外，所有接口都要求 `Authorization: Bearer <Authelia access_token>`。BFF 自己产生的错误统一返回 `{"code": "...", "message": "..."}`（message 可直接展示给用户）；Miniflux 与 Artalk 的响应体原样透传。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 健康检查 |
| GET | `/api/auth/config` | `{registrationEnabled, passwordMinLength, passwordResetUrl?}`，登录页用 |
| POST | `/api/auth/login` | `{username,password}` → `{tokens:{accessToken,refreshToken,expiresIn}, user}`；错误码 `INVALID_CREDENTIALS`(401)、`INTERACTION_REQUIRED`(403，账号要求二次验证)；按 IP 限流 |
| POST | `/api/auth/refresh` | `{refreshToken}` → 新的 tokens（refresh token 会轮换）；失效返回 401 `SESSION_EXPIRED` |
| POST | `/api/auth/logout` | 可带过期的 Bearer + `{refreshToken?}` → 204；在 Authelia 吊销两个 token 并清掉 BFF 缓存，退出后旧 token 立即失效 |
| POST | `/api/auth/register` | `{username,email,password,displayName?}` → 201 `{username, provisioning}`；按 IP 限流 |
| POST | `/api/auth/password` | `{oldPassword,newPassword}` → 204；按用户限流 |
| GET | `/api/auth/me` | 当前用户（userinfo claims） |
| POST | `/api/auth/token/exchange` | `{component:"artalk"}` → Artalk JWT；miniflux / calibre 由 BFF 代理，不下发凭据 |
| * | `/api/miniflux/{path}` | 透传到 Miniflux `/v1/{path}`；只放行 me、entries、feeds、categories、icons、discover、enclosures |
| PUT | `/api/miniflux/entries/{id}/read` | 标记单篇已读 |
| GET | `/api/calibre/books?q=&offset=&limit=` | 书目分页（limit 1–100），q 匹配书名或作者 |
| GET | `/api/calibre/books/{id}` | 详情（含简介） |
| GET | `/api/calibre/books/{id}/cover` | 封面 |
| GET | `/api/calibre/books/{id}/file/{format}` | 下载书籍文件 |
| GET/PUT | `/api/calibre/progress/{bookId}` | 阅读进度 `{format, position, percent?}`；409 `CALIBRE_USER_NOT_PROVISIONED` 表示需要先 activate |
| POST | `/api/calibre/activate` | `{password}`：代用户登录一次 CWA，让它建号 |
| GET | `/api/artalk/comments?page_key=&limit=&offset=&sort_by=` | 评论列表 |
| POST | `/api/artalk/comments` | `{pageKey, content, pageTitle?, replyTo?}` |

## 3. 部署前准备（一次性）

以下改动都在 NAS 上完成，**全部只在内网 / 现有 nginx 上进行，不做任何端口映射到公网以外的暴露**。

1. **Docker 网络**：BFF 需要和 lldap、miniflux、artalk、calibre-web-automated 处在同一个网络，且容器名可以互相解析。用 `docker network ls` 确认网络名，填进 `.env` 的 `DOCKER_NETWORK`。如果几个组件分属不同的 compose 项目，先 `docker network create sso`，再把各组件 `docker network connect sso <容器>` 接进来。
2. **Authelia**：把 `server/deploy/authelia-client.yml` 合并到 Authelia 配置里。要点：`public: true`、`consent_mode: 'implicit'`（否则 BFF 代登录会卡在授权确认页）、`require_pkce: true`、`authorization_policy: 'one_factor'`（App 没有二次验证界面）。`state` 必须 ≥ 8 个字符（BFF 和脚本都用 32 位十六进制）。`.env` 的 `OIDC_ISSUER` 要填 Authelia 的**公网地址**：BFF 代登录时拿到的会话 cookie 只对 Authelia 的 session 域名有效。Authelia 的 regulation（多次输错封禁）按用户名生效，BFF 另有按 IP 的登录限流。
3. **LLDAP**：确认 `LLDAP_DEFAULT_GROUPS` 里的每个组都已存在（按 `displayName` 匹配），至少包含 CWA LDAP 过滤条件要求的那个组。
4. **Miniflux**：用管理员账号登录，在「设置 → API 密钥」生成一个 key，填进 `MINIFLUX_ADMIN_TOKEN`。**不要给 Miniflux 开 `DISABLE_LOCAL_AUTH`**：BFF 要用 Basic 认证代用户调 API，关掉本地认证会导致所有 Miniflux 接口 401。设计稿里的 `AUTH_PROXY_*` 与 `TRUSTED_REVERSE_PROXY_NETWORKS` 不再需要，可以删掉。
5. **CWA**：保持 `config_ldap_auto_create_users=1`。把 `PUID/PGID` 设为 CWA 容器的 PUID/PGID，否则 BFF 写不了 app.db。
6. **nginx**：参考 `server/deploy/nginx-bff.conf` 给 BFF 单独开一个 server 块（示例用 8462 端口，证书沿用 acme）。`proxy_set_header X-Real-IP $remote_addr` 必须保留，限流依赖它。nginx 如果也跑在 docker 里并接入了同一网络，直接 `proxy_pass http://voicebook-bff:8080`，并删掉 compose 里的 `ports`。
7. **生成密钥**：`MINIFLUX_PASSWORD_SECRET` 用 `openssl rand -hex 32` 生成，之后不要随意更换（更换的后果见第 7 节）。

## 4. 部署

### 一键部署（推荐）

本机需要：JDK 17、ssh/scp（Windows 自带 OpenSSH）、tar；NAS 需要：docker 与 compose。建议先配好 ssh 免密登录。

```powershell
# Windows
.\scripts\deploy-bff.ps1 -NasHost admin@192.168.1.10          # 群晖等需要 sudo 跑 docker 时加 -Sudo
```

```bash
# Linux / macOS / Git Bash
BFF_NAS_HOST=admin@192.168.1.10 ./scripts/deploy-bff.sh      # 需要 sudo 时加 --sudo
```

脚本的流程是：本机跑测试并执行 `:server:installDist` → 打包成 `server/build/voicebook-bff.tgz` → 上传到 NAS 的 `~/voicebook-bff/` → 在 NAS 上执行 `remote-up.sh`（`docker compose up -d --build`，然后等待健康检查通过）。

**首次部署**时脚本会在 NAS 上生成 `~/voicebook-bff/.env` 然后退出（退出码 3）。按注释填好后重跑一次即可。`.env` 只存在于 NAS，不会被覆盖，也不要提交到 git。

镜像不在 NAS 上编译 Kotlin：Gradle 配置阶段需要 Android SDK，所以在开发机上构建好，NAS 只负责打一个运行时镜像（`eclipse-temurin:17-jre`，内存上限 256MB）。

### 手动部署

```bash
./gradlew :server:installDist
# 把 server/build/install/bff 目录和 server/deploy/ 下的 Dockerfile、docker-compose.yml、.env.example、remote-up.sh
# 放到 NAS 同一个目录，保证 bff/ 与 Dockerfile 同级
cp .env.example .env && vi .env
sh remote-up.sh            # 或 docker compose up -d --build
```

## 5. 验证

1. 跑冒烟测试（用一个测试账号经 BFF 登录，结束时会验证刷新与退出）：
   ```powershell
   .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462 -Username smoke01 -Password 'Passw0rd123'
   .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462 -RegisterUser smoke01 -RegisterPassword 'Passw0rd123'
   .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462 -PostComment    # 会真实发一条评论
   ```
   也可以先用 `.\scripts\bff-token.ps1` 走浏览器 PKCE 拿 token（需在 Authelia 客户端的 `redirect_uris` 加 `http://localhost:8765/callback`），再不带 `-Username` 运行，用来排查「BFF 代登录失败但浏览器登录正常」的情况。

   脚本依次检查：健康检查、无 token 返回 401、错误密码被拒、登录、`/auth/me`、Miniflux（首次调用会自动建号）、Miniflux 管理接口被屏蔽、calibre 书目 / 详情 / 封面 / 进度、Artalk 评论列表与换票。calibre 进度返回 409 表示该用户还没在 CWA 建号，调用一次 `POST /api/calibre/activate` 即可。
3. 用注册出来的账号分别登录 Artalk / calibre / Miniflux 的网页（内网访问），确认三边都有这个账号。
4. 外网访问各组件原端口应当失败（设计稿 P0 / P6 的收尾要求）。

## 6. 本地开发

```powershell
./gradlew :server:test          # 单元测试 + 路由测试（MockEngine 模拟上游，临时 SQLite 模拟 calibre）
./gradlew :server:run           # 本地启动，读取 server/.env.local（键名同 .env.example，已加入 gitignore）
```

在内网开发机上，可以在 `.env.local` 里把 `LLDAP_HTTP_URL`、`MINIFLUX_URL` 等指向 NAS 的内网地址；`CALIBRE_LIBRARY_DIR` / `CALIBRE_APP_DB` 指向一份拷贝下来的数据库，**不要直接指向 NAS 上正在使用的 app.db**。

约定：

- **DTO 放 `core/api-contract`**，App 和 BFF 共用；字段只增不改，新增字段要给默认值，保证新旧版本 App 兼容。
- 上游调用统一用 `upstreamHttpClient()`（不自动抛非 2xx、不跟随重定向）。连接失败包装成 `BffException.upstream(...)`，对外表现为 502，并且要单独放行 `CancellationException`。
- 注释里不要写出斜杠紧跟星号的组合（比如 Miniflux 的 v1 通配路径）。Kotlin 块注释可以嵌套，KDoc 里出现这个组合会被当成新注释的开头，导致 `Unclosed comment`。
- 仓库设置了 `core.autocrlf=true`，`.gitattributes` 已把 `*.sh` 和 `server/deploy/**` 固定为 LF，新增 NAS 端脚本时放在这些路径下。

### 新增一个接口

1. 在 `BffRoutes` / `BffModels` 里加路径和 DTO。
2. 在对应的 gateway 里加上游调用，在 `routes/` 里加路由，需要登录的放进 `authenticate(AUTH_OIDC)`。
3. 在 `RoutesTest` 里补一条用例（MockEngine 里加上游响应）。

### 接入一个新组件

1. 在 `config/` 加配置，在 `.env.example` 加变量。
2. 新建 `xxx/XxxGateway.kt`。优先选择「管理员凭据 + 按用户代调」或「用户 OIDC token 换组件票据」，**不要在 BFF 里保存用户明文密码**。
3. 如果需要注册时就建号，在 `BffServices.create` 的 `provisioners` 里加一项。它失败不会让注册失败，所以组件侧也要能在首次使用时自己补建。
4. 在 `BffComponents` 里加组件名，并更新 `bff-smoke.ps1`。

### App 侧（账号部分已完成）

| 位置 | 作用 |
|---|---|
| `core/network/.../bff/BffAuthApi.kt` | 调 BFF 账号接口，失败统一抛 `BffApiException`（status 0 表示网络不通） |
| `feature/auth/.../auth/AuthController.kt` | 登录 / 注册（成功后自动登录）/ 改密 / 退出；`validAccessToken()` 在过期前 60 秒用 refresh token 静默续期，refresh 失效则清空会话 |
| `feature/auth/.../auth/AuthState.kt` | 持久化 BFF 地址、当前用户、token（`key=value` 行格式；旧版本地假账号数据解码后视为未登录） |
| `feature/auth/.../screens/auth/` | 登录（含「服务器」折叠区修改 BFF 地址）、注册（新增邮箱）、找回密码（跳转 `PASSWORD_RESET_URL`）、修改密码（「我的」→ 账户 → 修改密码） |

默认 BFF 地址是 `DEFAULT_BFF_URL`（`https://nas.wangyl.work:8462`），用户在登录页改过后会持久化。token 目前存在各平台的普通偏好存储里（与原来的本地账号同一位置），若需要更高安全性，Android 可换成 Keystore 加密存储。

接下来 P5 改造内容接口时：

- 所有请求先 `authController.validAccessToken()` 取 token 加 Bearer 头；拿到 null 或 BFF 返回 401 就跳登录页。
- 进度接口返回 409 `CALIBRE_USER_NOT_PROVISIONED` 时，提示用户输入一次密码，调 `/api/calibre/activate`。
- `MinifluxApi` / `CalibreWebApi` 的 baseUrl 改成 BFF，去掉 X-Auth-Token / Basic 认证，改为 Bearer；封面请求同样带 Bearer 头。

## 7. 运维

| 操作 | 命令（在 NAS 的 `~/voicebook-bff` 目录下） |
|---|---|
| 查看状态 | `docker ps --filter name=voicebook-bff`（STATUS 应为 healthy） |
| 查看日志 | `docker logs -f --tail 200 voicebook-bff`（json-file，单文件 10MB，保留 3 个） |
| 修改配置 | 编辑 `.env` 后执行 `docker compose up -d`（不需要重新构建） |
| 升级 | 在开发机上重跑一键部署脚本 |
| 回滚 | `docker tag voicebook-bff:previous voicebook-bff:latest && docker compose up -d --no-build` |
| 调试日志 | `.env` 里设 `LOG_LEVEL=DEBUG` 后执行 `docker compose up -d` |

- **无状态**：BFF 不落盘，所有缓存（token 校验结果、Artalk JWT、Miniflux 已开通用户）都在内存里，重启只会让第一次请求稍慢。唯一需要备份的是 `.env`。
- **token 撤销延迟**：userinfo 校验结果缓存 `OIDC_CACHE_TTL_SECONDS`（默认 60 秒），经 BFF `logout` 退出会立即清缓存；但在 LLDAP 里禁用用户、或在 Authelia 侧吊销 token 后，最多还能再访问这么长时间。
- **更换 `MINIFLUX_PASSWORD_SECRET`**：重启后每个用户第一次访问时会自动把 Miniflux 密码重置为新的派生值，不需要手动处理。但如果有人用旧的派生密码直连 Miniflux，会失效。
- **Miniflux 管理员 key 失效**：日志里会出现 `Miniflux 管理员 token 无效或无权限`，重新生成 key 后更新 `.env`。
- **LLDAP 管理员密码修改**：同步更新 `.env` 里的 `LLDAP_ADMIN_PASSWORD`，否则注册和改密都会返回 502。
- **calibre 升级**：CWA / calibre 升级后，确认 `bookmark`、`kobo_reading_state`、`kobo_bookmark`、`user` 这几张表的结构没有变化，然后跑一遍冒烟测试。Kobo 百分比写入失败只会记一条 warn 日志，不影响 `bookmark` 的写入。

### 常见问题

| 现象 | 排查 |
|---|---|
| 登录 403 `INTERACTION_REQUIRED` | 授权端点把 BFF 重定向回了 Authelia 页面：检查客户端是否 `consent_mode: 'implicit'`、access_control 对该用户是否要求 `two_factor` |
| 登录 502「授权端点未返回重定向」/「state 不匹配」 | `OIDC_ISSUER` 不是 Authelia 的公网地址（session cookie 域名对不上），或中间 nginx 改写了重定向 |
| 浏览器能登录但 App 登录一直 401 | 确认用户名是 LLDAP 的 uid（BFF 会转小写），以及账号是否已被 Authelia regulation 临时封禁 |
| 所有接口 502 `UPSTREAM_OIDC` | BFF 容器访问不到 `OIDC_USERINFO_URL`。NAS 上访问自己的公网域名可能出现回环问题，可以改成 Authelia 的内网地址，但要保证 Authelia 认可该地址的 Host / 协议 |
| 带 token 仍然 401 | token 过期；或 userinfo 里没有 `preferred_username`（授权时需要带 `profile` scope，也可以用 `OIDC_USERNAME_CLAIM` 换成别的 claim） |
| 注册 502 `UPSTREAM_LLDAP` | 检查 LLDAP 管理员账号密码、`LLDAP_BASE_DN`、`LLDAP_DEFAULT_GROUPS` 里的组是否存在 |
| 注册成功但 provisioning.calibre 为 false | 按 message 提示：CWA 拒绝登录（多半是分组不对），或 `config_ldap_auto_create_users` 没开 |
| calibre 接口 502 `找不到数据库` | 挂载路径不对；`CALIBRE_LIBRARY_HOST_DIR` 要指向包含 metadata.db 的目录 |
| 写进度 500 / readonly database | `PUID/PGID` 和 CWA 不一致，或者 CWA config 目录不可写 |
| Miniflux 接口 502 `建号/同步失败` | 管理员 key 无效；或者 Miniflux 开了 `DISABLE_LOCAL_AUTH` |
| Artalk 发评论 502 `SSO 换票失败` | Artalk 的 `auth.sso.issuer` 配置，以及 Artalk 能否访问 Authelia |
