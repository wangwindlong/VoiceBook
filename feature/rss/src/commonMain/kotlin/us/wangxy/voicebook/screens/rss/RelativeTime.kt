package us.wangxy.voicebook.screens.rss

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 相对时间（x 分钟前 / 小时前 / 天前），纯 common 计算。 */
@OptIn(ExperimentalTime::class)
fun relativeTime(publishedAt: Long): String {
    val diff = Clock.System.now().toEpochMilliseconds() - publishedAt
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / 60 / 24} 天前"
        else -> "${minutes / 60 / 24 / 30} 个月前"
    }
}
