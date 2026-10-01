# CWA 独立连接：手机与网页阅读进度

手机 A、手机 B 需要安装包含本次修复的客户端，独立连接同一个 CWA 地址、同一个书库账号，并开启 CWA 的 KOReader Sync。手机翻页后通过 `/kosync/syncs/progress` 发送文件的 partial MD5、位置和 0..1 的百分比。

客户端会校验返回 JSON、时间戳、书籍关联；失败时保留本地待同步记录，在阅读页显示原因。退出阅读或切到后台会补发待同步进度。另一台手机打开书籍或重新回到阅读页时，会读取最新服务端进度；历史列表刷新也会读取已建立文件标识的书籍。离线产生的较新位置不会被旧服务端记录覆盖。

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
