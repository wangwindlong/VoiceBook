# UI 稿实现与接口边界（2026-09-30）

本次以用户提供的 16 屏 UI 稿为视觉参照，重点实现用户指定的首页、阅读、订阅管理、文章详情、设置、统一登录和右侧栏。保留工作区已有的首页导航与 RSS 同步修改。没有把稿中的示例书籍、文章、评论和点赞数当作真实数据写入应用。

## 已实现的页面与交互

| 页面 | 行为 |
| --- | --- |
| 首页 | 按用户最终选择保留最新的三个入口玻璃卡片版：阅读/资讯/AI，菜单打开侧栏、四列真实书籍推荐、最新两条资讯；搜索调用书籍/RSS 数据层 |
| 书架 | 三列书籍、书名作者、未读/已读进度、搜索、全部/电子书/经典/文学/科幻筛选、刷新、书城入口、上传浮动按钮 |
| 书籍详情 | 点封面打开圆角操作浮层，展示封面/书名/作者/格式/已有简介，开始阅读、评论、分享书籍信息 |
| 阅读 | 保留 EPUB/TXT/PDF 阅读引擎；点击正文中央显示顶部工具条和悬浮底部操作卡；进度、目录、设置、听书、夜间、字体调整 |
| 阅读底部导航 | 书架、继续阅读、最近阅读书籍的评论、更多工具；无阅读历史时有提示 |
| 评论页 | 独立全屏评论页、真实总数、精彩/全部评论区域、头像、时间、点赞、回复、输入发送、验证码；隐藏原有模拟阅读统计 |
| 订阅源 | 搜索名称/地址、按分类过滤、手动修改分类、取消订阅确认、添加订阅源；同步设置折叠，仍支持统一账号/本地/自有 Miniflux |
| 资讯详情 | 图片、标题、来源/时间、正文、收藏、BFF 点赞、评论、系统分享、同源相关文章跳转；失败不阻止继续阅读 |
| 设置 | 明亮/深色/自动、皮肤预览、六色主题、夜间/封面取色/系统字体开关；账号、服务器、日志和调试入口保留 |
| 登录 | 复用现有 BFF 登录、注册、刷新、退出、改密、找回密码入口，使用统一的字体和配色 |
| 右侧栏 | 右侧滑入、遮罩、圆角、账号区、阅读/资讯/AI、小工具和设置分组；点击跳转、拖动收起 |
| 本地小工具 | 简单专注计时、持久化便签、2048，供侧栏入口使用，无服务端依赖 |

视觉规格使用 dp/sp，随屏幕密度缩放：主色 #3478F6、主文字 #172443、辅助文字 #8391AB；标题 20–22sp、小标题 16sp、正文 14sp、辅助文字 10–12sp；页面边距 16–20dp；封面圆角 6dp、卡片 12–14dp、详情/阅读操作卡 16dp、侧栏 20dp；首页沿用用户确认的玻璃卡片布局。中文使用系统字体，素材复用仓库已有山景，书封/资讯图来自真实数据；不是原稿字体和示例素材的逐像素复制。

## 已存在且继续使用的接口

除认证公开端点外，统一使用 `Authorization: Bearer <access_token>`；以下都是 server 模块 BFF 路径，不是 server2。

| 方法 | 路径 | 页面/用途 | 实际依赖 |
| --- | --- | --- | --- |
| GET | `/api/auth/config` | 登录页配置 | BFF |
| POST | `/api/auth/login`、`refresh`、`logout` | 统一登录和会话 | BFF + Authelia |
| POST | `/api/auth/register`、`password` | 注册、改密 | BFF + LLDAP/Authelia；组件开通为适配逻辑 |
| GET | `/api/auth/me` | 账号资料 | BFF + Authelia |
| GET | `/api/calibre/books?q=&category=&offset=&limit=` | 书籍列表、搜索、分类、推荐 | BFF 私有上传库 + 可选 Calibre metadata.db |
| GET | `/api/calibre/books/{id}` | 详情 | 同上 |
| GET | `/api/calibre/books/{id}/cover` | Calibre 封面 | Calibre 图书目录 |
| GET | `/api/calibre/books/{id}/file/{format}` | 下载阅读 | 私有上传库或 Calibre 文件 |
| GET/PUT | `/api/calibre/progress/{bookId}` | 阅读位置 | 生产环境使用 BFF content.db；未装配 ContentStore 的兼容宿主使用旧 Calibre 进度适配器 |
| GET | `/api/miniflux/feeds` | 用户订阅源 | BFF 订阅关系 + Miniflux 源信息 |
| POST/DELETE | `/api/miniflux/feeds`、`feeds/{id}` | 订阅/取消订阅 | BFF + Miniflux |
| PUT | `/api/miniflux/feeds/{id}/refresh` | 刷新订阅源 | Miniflux |
| GET | `/api/miniflux/entries?feed_id=&search=&offset=&limit=` | 资讯列表、搜索、同源相关文章 | BFF 用户范围/状态过滤 + Miniflux 内容 |
| GET | `/api/miniflux/entries/{id}` | 资讯内容 | 同上 |
| PUT | `/api/miniflux/entries`、`entries/{id}/bookmark` | 已读/收藏 | BFF 用户状态 SQLite；内容来自 Miniflux |
| GET/POST | `/api/artalk/comments` | 图书和资讯评论、回复 | BFF 身份适配 + Artalk |
| GET/POST | `/api/artalk/votes/comment/{id}`、`.../{id}/up` | 评论点赞/取消 | Artalk；BFF 按账号隔离 |
| GET/POST | `/api/artalk/captcha`、`captcha/verify` | 评论反垃圾验证码 | Artalk |

## 本次新增接口

| 方法 | 路径/请求 | 响应 | 是否需要三个内容组件 |
| --- | --- | --- | --- |
| POST | `/api/calibre/uploads?filename=&title=&author=&category=`，`application/octet-stream` 原始文件 | 201 `CalibreBook` | 不需要 |
| GET | `/api/articles/reactions/{key}` | `{likes, liked}` | 不需要 |
| PUT | `/api/articles/reactions/{key}`，`{liked: true/false}` | 操作后的 `{likes, liked}` | 不需要 |
| GET | `/api/feeds/categories` | `{categories: {key: category}}` | 不需要 |
| PUT | `/api/feeds/categories/{key}`，`{category}` | 204 | 不需要 |

文章/源的任意 GUID 通过共享 `contentKey` 转为稳定的 32 位十六进制路由键，不把包含 `/` 的 URL 放入路径；相同后端 GUID 在各端保持一致。点赞 PUT 传目标状态，多次重试不会重复计数。分类和阅读进度按用户名存储，点赞计数按文章共享，已点赞状态按用户名隔离。

上传仅支持 EPUB/PDF/TXT，最大 64MiB。BFF 流式写入临时文件并验证基础格式；最终文件名由 UUID 生成。上传的公开书籍 ID 为负数，避免与 Calibre 正整数 ID 冲突；列表、详情、文件和进度都校验所有者。上传不会写 Calibre metadata.db，因此不会自动出现在 Calibre Web 的书目中。Android 使用系统文件选择器，桌面使用文件对话框；当前 iOS/Web 文件选择器仍需平台接入，界面会明确提示。

## BFF 与上游服务的职责

- **已放入 BFF**：私有图书上传/存储/下载，统一分页及搜索，图书分类过滤，阅读进度，文章点赞，用户订阅分类，已有订阅关系和已读/收藏状态，用户身份映射及统一错误处理。
- **Miniflux**：定时抓取 RSS、解析、抓正文、附件/图标、源健康状态。UI 的同源相关文章复用已有 BFF 文章筛选，不新增推荐服务；未登录本地模式继续由客户端抓取。
- **Calibre**：已有书库的元数据、封面、电子书文件。App 自己上传的书无需 Calibre；需要格式转换、自动提取 EPUB/PDF 封面、向 Calibre Web 入库时，可增加 BFF 任务适配 `calibredb add/ebook-convert`，本次未执行该入库/转换。
- **Artalk**：评论存储、回复树、评论点赞、审核、反垃圾验证码。文章点赞由 BFF 独立实现；评论继续复用 Artalk，而不是在 BFF 再建一套不兼容的评论系统。
- **客户端**：阅读排版/分页、浮层和侧栏动画、系统分享、外观偏好、简单本地工具。系统分享直接调 Android 分享面板，不需要“分享接口”。

接口能力核对资料：[Miniflux 官方 API](https://miniflux.app/docs/api.html)、[Calibre 官方命令行文档](https://manual.calibre-ebook.com/generated/en/calibredb.html)、[Artalk 官方 API](https://artalk.js.org/http-api)。上面列出的 BFF 分工是本仓库的实现选择，而非上游服务限制。

## 部署与验证

新增数据放在 BFF_STATE_DB 同目录的 `content.db` 和 `books/`，复用现有 `/data` 可写挂载；备份时应同时备份数据库和文件。nginx 示例已将 `client_max_body_size` 调至 64m。需要重新部署 BFF 并应用 nginx 配置，旧在线 BFF 不含新增端点；本次没有发布服务端或代用户发表评论/上传真实书籍。

测试覆盖私有上传隔离、持久化、格式拒绝、下载、分类、阅读进度、点赞幂等与账号隔离，以及原有登录/Miniflux/Artalk 路由，共 166 项测试通过；另验证共享 JVM 编译、Android APK 构建和设备页面布局。主题色保存回归测试覆盖此前皮肤类型兼容问题。RSS 同步测试的日期 fixture 使用真实 ISO 时间，避免无效日期回退到当前时刻导致顺序随机。

评论页按 50 条分页并提供加载更多；精彩区域通过 Artalk vote 排序单独获取。计时器为页面内工具，后台通知/历史同步没有增加。跨设备进度以 BFF 成功保存的位置为准，离线时先保留本地位置。
