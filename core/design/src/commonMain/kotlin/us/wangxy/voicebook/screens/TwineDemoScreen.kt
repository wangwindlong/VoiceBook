package us.wangxy.voicebook.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.wangxy.voicebook.twine.TwineLink
import us.wangxy.voicebook.twine.TwineMenu
import us.wangxy.voicebook.twine.TwineMenuItem
import us.wangxy.voicebook.twine.TwineMenuSeparator
import us.wangxy.voicebook.twine.TwinePassage
import us.wangxy.voicebook.twine.TwineSheet
import us.wangxy.voicebook.twine.TwineStoryLink
import us.wangxy.voicebook.twine.TwineStoryMap
import us.wangxy.voicebook.twine.TwineStoryNode
import us.wangxy.voicebook.theme.LocalTwineTokens

/**
 * Twine 组件演示页(临时入口,TODO:组件铺开到各页面后移除)。
 * 集中展示 TwinePassage / TwineLink / TwineMenu / TwineSheet / TwineStoryMap
 * 的全部动效,便于逐皮肤核对令牌表现。
 */
@Composable
fun TwineDemoScreen(navigateBack: () -> Unit) {
    val tokens = LocalTwineTokens.current
    // 段落流:点"继续"显现下一段(Twine 叙事核心交互)
    var revealedCount by rememberSaveable { mutableIntStateOf(1) }
    var showSheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var chosenBranch by rememberSaveable { mutableStateOf("未选择") }

    // Story map 示例数据:位置存本地状态,拖动节点立即生效
    var nodes by remember {
        mutableStateOf(
            listOf(
                TwineStoryNode("start", "起风了", 40f, 40f, 2),
                TwineStoryNode("inn", "客栈夜谈", 260f, 20f, 1),
                TwineStoryNode("road", "官道追兵", 260f, 160f, 1),
                TwineStoryNode("well", "枯井密道", 40f, 200f, 1),
                TwineStoryNode("duel", "桥上决战", 500f, 100f, 2),
                TwineStoryNode("end", "故事终章", 720f, 220f, 0),
            ),
        )
    }
    val links = remember {
        listOf(
            TwineStoryLink("start", "inn"),
            TwineStoryLink("start", "road"),
            TwineStoryLink("inn", "duel"),
            TwineStoryLink("road", "well"),
            TwineStoryLink("well", "duel"),
            TwineStoryLink("duel", "end"),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = navigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("Twine 组件演示", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f))
            TwineMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                anchor = {
                    Text(
                        "菜单 ⌄",
                        style = TextStyle(color = tokens.inkAccent, fontSize = 16.sp),
                        modifier = Modifier
                            .clickable { menuExpanded = true }
                            .background(tokens.paper, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
            ) {
                TwineMenuItem("走客栈线", onClick = { chosenBranch = "客栈夜谈"; menuExpanded = false })
                TwineMenuItem("走官道线", onClick = { chosenBranch = "官道追兵"; menuExpanded = false })
                TwineMenuSeparator()
                TwineMenuItem("打开抽屉", onClick = { showSheet = true })
            }
        }

        TwinePassage {
            Text(
                "当前分支:$chosenBranch",
                style = TextStyle(color = tokens.inkFaded, fontSize = 12.sp),
            )
            Text(
                "纸面段落卡入场时会淡入并轻轻上浮;这段文字读起来应该像印在纸上," +
                    "而不是浮在一块灰色面板里。切换不同皮肤(折纸/潮汐/霓虹)后回来再看," +
                    "纸与墨的颜色会跟着整套令牌变化。",
                style = TextStyle(color = tokens.ink, fontSize = 15.sp, lineHeight = 24.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (revealedCount >= 2) {
            TwinePassage(revealed = true) {
                Text(
                    "第二段:点击下面的链接会显现第三段 —— 显现动画由令牌的时长与阻尼曲线控制。",
                    style = TextStyle(color = tokens.ink, fontSize = 15.sp, lineHeight = 24.sp),
                )
            }
        }
        if (revealedCount >= 3) {
            TwinePassage(revealed = true) {
                Text(
                    "第三段(终):分支已定,故事讲完了。",
                    style = TextStyle(color = tokens.ink, fontSize = 15.sp, lineHeight = 24.sp),
                )
            }
        } else {
            TwineLink(
                text = "→ 继续读下去",
                onClick = { revealedCount = revealedCount + 1 },
                style = TextStyle(fontSize = 15.sp),
            )
        }

        Text(
            "Story map:拖节点、拖画布平移、双指缩放",
            style = TextStyle(color = tokens.inkFaded, fontSize = 12.sp),
        )
        TwineStoryMap(
            nodes = nodes,
            links = links,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(tokens.paper.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(4.dp),
            onNodeMove = { id, x, y ->
                nodes = nodes.map { if (it.id == id) it.copy(x = x, y = y) else it }
            },
            onNodeClick = { node -> chosenBranch = node.title },
        )
    }

    TwineSheet(
        visible = showSheet,
        onDismiss = { showSheet = false },
        peekFraction = 0.5f,
    ) {
        Text(
            "Twine 抽屉",
            style = TextStyle(color = tokens.ink, fontSize = 18.sp),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "下拉这里的拖拽条、点遮罩或按返回键都会收起;" +
                "往上拖可以展开到全高。纸面与拖拽条颜色来自当前皮肤。",
            style = TextStyle(color = tokens.inkFaded, fontSize = 14.sp, lineHeight = 22.sp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}
