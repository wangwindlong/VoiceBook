package us.wangxy.voicebook.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * 资讯小组件：未读数 + 最新三条标题。数据来自同步后写入的
 * SharedPreferences("rss_widget") 快照（core:data 的 WidgetSnapshotStore）。
 */
class RssGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("rss_widget", Context.MODE_PRIVATE)
        val raw = prefs.getString("snapshot_json", null)
        val unread = Regex("\"unread\"\\s*:\\s*(\\d+)").find(raw.orEmpty())?.groupValues?.get(1) ?: "0"
        val titles = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").findAll(raw.orEmpty())
            .take(3)
            .map { it.groupValues[1] }
            .toList()

        provideContent {
            GlanceTheme {
                Box(
                    modifier = GlanceModifier.fillMaxSize().padding(10.dp).background(GlanceTheme.colors.surface),
                ) {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                "资讯",
                                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Text(
                                "未读 $unread",
                                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(6.dp))
                        if (titles.isEmpty()) {
                            Text(
                                "暂无文章",
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                            )
                        }
                        titles.forEach { title ->
                            Spacer(modifier = GlanceModifier.height(4.dp))
                            Text(
                                title,
                                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

class RssWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RssGlanceWidget()
}
