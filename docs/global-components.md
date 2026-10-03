# 全局组件

组件在 `core/design`，App 根节点提供 `LocalGlobalUi` 和 `LocalGlobalNotice`，所有页面可直接使用。颜色均读取当前 MaterialTheme，因此随皮肤和亮暗模式更新。

- `FloatingNavigation`：自定义 tab 列表、选中项和回调。App 捕获页面作为 Haze 模糊源，导航覆盖在内容之上。根节点 nestedScroll 监听用户滚动，不消费滚动；向上隐藏、向下显示，阅读/文章页自动隐藏。
- `LocalGlobalUi.current.showMenu(listOf(ContextAction("复制") { ... }))`：浮动上下文操作菜单；卡片通过 `combinedClickable` 长按调用。点击外部或返回关闭，操作回调执行前关闭菜单。
- `LocalGlobalUi.current.searchVisible = true`：打开全局搜索。搜索书籍和资讯，300ms 防抖，查询变化取消旧任务，支持部分失败。首页和底部搜索 tab 均有入口。历史仅在键盘提交或打开结果时记录，最多 12 条，可清空；Android、iOS、桌面和浏览器保存历史，浏览器存储不可用时退回内存。
- `LocalGlobalUi.current.showSheet { ... }`：任意 Compose 内容的底部抽屉，支持拖拽、遮罩和返回关闭，处理键盘及系统导航区。
- `val remind = rememberGlobalReminder()`，`remind("已保存", "查看") { navigate(...) }`：全局轻量通知，支持动作跳转、手动关闭和自动消失，共享通知队列。

当前首页书籍及资讯卡片已接入打开/复制/分享长按操作。其他页面可复用以上接口，菜单动作由业务页面定义。
