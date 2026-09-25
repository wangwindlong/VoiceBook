package us.wangxy.voicebook.voice.duplex

data class BackchannelConfig(
    /** Characters that on their own carry no intent: hesitations and listening noises. */
    val fillerChars: Set<Char> = "嗯哦噢喔啊呃额唔哼嘿哈呀诶欸哎唉啦嘛呢吧么恩喽嗷".toSet(),
    /** Whole phrases treated as backchannel even though they contain other characters. */
    val fillerPhrases: Set<String> = setOf("嗯哼", "是吗", "这样啊", "原来如此"),
    /** Longer hypotheses are never treated as backchannel, whatever they contain. */
    val maxChars: Int = 4,
)

/**
 * Recognizes "嗯", "哦哦", "啊？" and similar listening noises. While the assistant is speaking
 * they must neither duck nor interrupt the TTS; they are reported as backchannel instead.
 */
class BackchannelFilter(private val config: BackchannelConfig = BackchannelConfig()) {
    fun isBackchannel(text: String): Boolean {
        val normalized = buildString(text.length) {
            for (c in text) if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
        return when {
            normalized.isEmpty() -> true
            normalized.length > config.maxChars -> false
            normalized in config.fillerPhrases -> true
            else -> normalized.all { it in config.fillerChars }
        }
    }
}
