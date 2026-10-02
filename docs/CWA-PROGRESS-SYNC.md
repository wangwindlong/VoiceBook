# CWA 独立连接：手机与网页阅读进度

手机 A、手机 B 需要安装包含本次修复的客户端，独立连接同一个 CWA 地址、同一个书库账号，并开启 CWA 的 KOReader Sync。手机翻页后通过 `/kosync/syncs/progress` 发送文件的 partial MD5、位置和 0..1 的百分比。

客户端会校验返回 JSON、时间戳、书籍关联；失败时保留本地待同步记录，在阅读页显示原因。退出阅读或切到后台会补发待同步进度。另一台手机打开书籍或重新回到阅读页时，会读取最新服务端进度；历史列表刷新也会读取已建立文件标识的书籍。没有待上传编辑时使用云端位置；离线编辑会与上次确认的云端位置比对，双方都变化时提示用户选择，避免静默覆盖。

## NAS Docker 上的 CWA 网页阅读器

CWA 的 EPUB 阅读脚本可能优先恢复浏览器 localStorage 的旧位置，只有没有本地进度和书签时才使用服务端百分比。客户端不能修改另一个浏览器的 localStorage，所以这一部分需要在 CWA 容器中应用修复。

已提供 `scripts/patch-cwa-reader-progress.py`：给阅读页面传入服务端更新时间，并按时间选择浏览器位置或服务端位置。旧浏览器记录没有时间戳时优先使用服务端；初始化过程不会把 0% 写回本地缓存。恢复到百分比对应的 EPUB 位置，不承诺不同阅读引擎逐字对齐。适用于包含 `cps/static/js/reading/epub-progress.js` 和 `kosyncPercent` 模板字段的 CWA 版本；其他版本会拒绝修改。

把脚本传到 NAS，SSH 登录 NAS，在脚本所在目录执行（替换容器名）：

```sh
CWA_CONTAINER=calibre-web-automated
docker cp patch-cwa-reader-progress.py "$CWA_CONTAINER":/tmp/patch-cwa-reader-progress.py
docker exec -u 0 "$CWA_CONTAINER" python3 /tmp/patch-cwa-reader-progress.py /app/calibre-web-automated
# 仅在上一步显示 Patched / Already patched 后重启：
docker restart "$CWA_CONTAINER"
```

若容器内应用路径不同，替换最后一个参数。脚本会先核对所有文件，备份到各文件旁的 `.voicebook-backup`，再修改。已经修改过则直接返回，不重复应用。重启后强制刷新网页阅读器；已打开的页面需要重新加载，修复不会实时推送位置。

恢复原版时，把三个 `.voicebook-backup` 文件复制回原文件，再重启；没有对代码目录做外部挂载的容器，也可以通过重新创建原镜像恢复。升级或重建容器通常会丢失此修复，需要重新核对版本再应用，或将修改的三个文件持久化为只读挂载。

## 验证

1. A、B、网页使用同一个 CWA 账号和同一本书。A 读到 10% 后退出阅读。
2. CWA 书籍详情应显示更新后的 KOReader 进度。若没有更新，先看客户端阅读页的同步错误；503 表示同步功能关闭，未关联书籍表示 CWA 缺少相匹配的文件校验码。
3. B 打开书籍，或切回前台的阅读页，应恢复 A 的章节与字符位置。
4. 在 CWA 网页重新加载该书。应用网页修复后，应从新的服务端百分比恢复；浏览器里更新更晚的阅读位置仍会保留。

客户端回归测试覆盖真实 JSON 序列化、A 上传 10% 后 B 覆盖旧历史、重启后拉取和补传、认证页伪成功、服务端禁用、未关联书籍和取消请求。网页修复在 CWA 上游 `3ce7bf80c8c66485eec7218c6b6c3fef8e6e6ba5` 源文件上验证，并通过 `scripts/tests/cwa-reader-progress.test.cjs` 的 7 个恢复场景。未连接实际 NAS 验证。

## NAS 部署记录（2026-10-01）

已通过 `ssh nas` 部署到 `calibre-web-automated` 容器，实际版本 v4.0.7，服务目录为 `/home/wangyl/work/service/calibre-web-automated`。部署前用该容器实际源码再次运行了 7 个网页恢复场景，全部通过。

现场发现 `koreader_sync_enabled=0`，书库也没有 `book_format_checksums` 表。备份数据库后已开启 KOReader Sync 并重启，CWA 自身完成建表和校验码回填：现有 5 个书籍文件均已建立索引。

已部署的文件（以下路径均相对于 NAS 服务目录）：

- `config/voicebook/patch-cwa-reader-progress.py`：网页补丁。
- `custom-cont-init.d/cwa-reader-progress.sh`：对应仓库 `scripts/cwa-reader-progress-init.sh`，在容器启动时应用补丁；不支持的源码版本会输出失败日志，继续启动 CWA。
- `config/voicebook/original-v4.0.7-20261001.tar`：修改前的三个源码文件。
- `config/voicebook/{cwa,app,metadata}-before-kosync-20261001.db`：开启同步前的 SQLite 一致性备份。

已确认启动钩子执行成功、登录页 HTTP 200、同步认证接口在未提供凭据时返回预期的 HTTP 401，以及 HTTP 实际返回的阅读脚本包含补丁。容器中的阅读模板语法检查通过。

此时服务端 KOSync 进度记录仍为 0 条。尚未操作真实手机上传或登录网页阅读，所以不能将以上部署检查视为三端联调通过。两台手机需更新客户端，A 阅读并退出后，再按上面的验证步骤检查；网页首次验证需强制刷新。

撤回网页补丁时，先移走 `custom-cont-init.d/cwa-reader-progress.sh`，再恢复源码备份并重启，避免启动钩子重新应用。数据库备份只用于故障恢复；不要为撤回网页补丁直接覆盖数据库，以免丢失部署后的用户数据。

协议与网页逻辑来源：[CWA KOSync](https://github.com/crocodilestick/Calibre-Web-Automated/blob/3ce7bf80c8c66485eec7218c6b6c3fef8e6e6ba5/cps/progress_syncing/protocols/kosync.py)、[CWA EPUB 进度恢复](https://github.com/crocodilestick/Calibre-Web-Automated/blob/3ce7bf80c8c66485eec7218c6b6c3fef8e6e6ba5/cps/static/js/reading/epub-progress.js)。

## EPUB 未关联提示排查（2026-10-02）

通过实际手机缓存、CWA 下载响应以及 NAS 容器数据库核对，书籍 6（《人工智能简史》）的关联缺失来自下载文件与书库原文件的差异：

- CWA `config_embed_metadata=1`，下载 EPUB 时通过 Calibre 导出并嵌入元数据。
- 书库原文件为 1,127,967 字节，partial MD5 为 `d28f2c8609bb935c28dc63bb2d5593bd`；启动回填在 `2026-10-01T00:00:42.772116+00:00` 登记的是这个版本。
- 手机缓存及实际下载文件均为 1,127,992 字节，partial MD5 为 `c079dc5e5e79b8fed9edccbf90f83aad`。首次排查时，这个校验码的 KOSync GET 返回位置 `17:1549` 和 21%，但缺少 `calibre_book_id`。
- 2026-10-01 排查中的重新下载触发 CWA 下载逻辑登记导出版本，在 `2026-10-01T13:50:06.706942+00:00` 加入 `book_format_checksums`，关联到书籍 6 / EPUB。
- 2026-10-02 从容器内调用同一账号的实际 KOSync GET，已返回 `calibre_book_id=6`、`calibre_book_format=EPUB`、位置 `17:1549`、21%。

因此不是手机 partial MD5 算法错误或旧缓存与当前下载不一致，而是原文件回填无法覆盖此前缓存的元数据导出版本。首次下载版本未被登记的具体历史原因未从现有日志确认；此前关闭 KOSync 时下载并缓存文件，与这一现象相符。此次没有手动修改数据库、隐藏错误或修改同步确认条件；登记由 CWA 自身的下载逻辑完成。其他此前缓存的导出版本仍可能遇到相同问题。

## 阅读进度同步规则更新（2026-10-02）

首页今日推荐和搜索卡片接入同一份阅读历史，显示已读百分比和进度条，并提供“同步进度”按钮。

客户端同步统一账号与独立 CWA 账号的阅读位置时，采用以下规则：

- 没有待上传编辑时，以可恢复的云端位置为准，不依赖手机时钟判断云端是否更新。
- 本地编辑先持久化。每次实际上传前读取云端并与本地持久保存的上次确认位置比对。
- 云端未变化时，离线编辑可自动上传；云端与本地同时变化，或旧待上传记录没有比对依据时，保留本地并展示“使用云端 / 保留本机”的选择框。
- 选择框在首页、书架和阅读页共用。等待选择时暂停该书进度写入与上传；选择时重新查询云端，使用云端后重建当前阅读位置。
- 应用回到前台时刷新历史，前台每 15 秒尝试重传待同步记录，每 30 秒刷新云端历史；真正进入后台后停止定时任务。网络恢复后无需重新打开书才能重试。
- 上传过程中产生的新本地编辑保持待上传，同时登记上一条上传成功的比对依据，避免误报本机更新为跨设备冲突。
- 账号或服务器切换后，旧冲突不能操作新连接。

听书位置独立存储在本地设置中，按服务器、账号与书籍区分；恢复听书优先使用独立位置，跟读自动翻页不覆盖阅读位置。切换账号/服务器时停止播放，旧音频回调不能写入新账号。听书位置尚无独立云端接口，因此此更新不提供跨设备听书位置同步。书签、笔记与书架的完整云端同步也不属于此次阅读位置更新。

当前 BFF / CWA 接口没有版本条件写入能力，所以“读取云端后再上传”仍存在两台设备恰好同时写入的短暂竞态窗口；不能视作服务器级原子冲突保护。CWA 无法解释其他阅读器位置格式时会保留本地并报告不兼容，不会强行上传覆盖。

回归覆盖离线重试、重启后的比对依据、双方变化时不上传、选择两端位置、弹窗期间第三设备更新、账号隔离、手机时钟超前、连续翻页上传竞态和听书/阅读位置独立。
