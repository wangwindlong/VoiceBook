package us.wangxy.voicebook.voice.duplex

data class EchoTextFilterConfig(
    /** Shorter hypotheses can't be judged reliably (e.g. "不对" may also occur in the prompt). */
    val minCharsToJudge: Int = 3,
    /** Share of the hypothesis' character bigrams found in the spoken text to call it echo. */
    val similarityThreshold: Float = 0.6f,
)

enum class EchoVerdict {
    /** Recognized text is our own TTS leaking back through the microphone. */
    Echo,

    /** Recognized text does not come from the TTS prompt. */
    User,

    /** Too short to decide and consistent with the prompt; don't trust it for barge-in yet. */
    Undetermined,
}

/**
 * Last line of defense after acoustic echo cancellation: compares what the recognizer heard
 * with what we are (or just were) saying.
 */
class EchoTextFilter(private val config: EchoTextFilterConfig = EchoTextFilterConfig()) {
    fun judge(recognized: String, spoken: String?): EchoVerdict {
        if (spoken.isNullOrBlank()) return EchoVerdict.User
        val heard = normalize(recognized)
        val said = normalize(spoken)
        if (heard.isEmpty()) return EchoVerdict.Undetermined
        val contained = said.contains(heard)
        if (heard.length < config.minCharsToJudge) {
            return if (contained) EchoVerdict.Undetermined else EchoVerdict.User
        }
        if (contained) return EchoVerdict.Echo
        return if (bigramCoverage(heard, said) >= config.similarityThreshold) EchoVerdict.Echo else EchoVerdict.User
    }

    private fun normalize(text: String): String = buildString(text.length) {
        for (c in text) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    private fun bigramCoverage(heard: String, said: String): Float {
        if (heard.length < 2) return 0f
        val saidBigrams = HashSet<String>(said.length)
        for (i in 0 until said.length - 1) saidBigrams += said.substring(i, i + 2)
        var hits = 0
        val total = heard.length - 1
        for (i in 0 until total) if (heard.substring(i, i + 2) in saidBigrams) hits++
        return hits.toFloat() / total
    }
}
