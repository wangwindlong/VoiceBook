package us.wangxy.voicebook.listen

/** What a spoken command asks the listen session to do. */
enum class ListenCommand {
    Pause,
    Resume,
    NextSentence,
    PreviousSentence,
    NextChapter,
    PreviousChapter,
    Faster,
    Slower,
    Stop,
    ;

    companion object {
        /** Passed to the recognizer so the short command words are favored. */
        val Hotwords = listOf("暂停", "继续", "下一句", "上一句", "下一章", "上一章", "快一点", "慢一点", "退出听书")

        /** Longer phrases first: "下一章" must win over "下一". */
        private val Phrases: List<Pair<List<String>, ListenCommand>> = listOf(
            listOf("下一章", "下章") to NextChapter,
            listOf("上一章", "上章") to PreviousChapter,
            listOf("下一句", "下句", "跳过") to NextSentence,
            listOf("上一句", "上句", "重复", "再读一遍", "再说一遍") to PreviousSentence,
            listOf("退出", "关闭", "结束听书", "不听了") to Stop,
            listOf("暂停", "停一下", "停止", "等一下") to Pause,
            listOf("继续", "播放", "开始") to Resume,
            listOf("快一点", "快点", "加速", "快一些") to Faster,
            listOf("慢一点", "慢点", "减速", "慢一些") to Slower,
        )

        fun parse(text: String): ListenCommand? {
            val cleaned = text.filter { it.isLetterOrDigit() }
            if (cleaned.isEmpty()) return null
            return Phrases.firstOrNull { (words, _) -> words.any { it in cleaned } }?.second
        }
    }
}
