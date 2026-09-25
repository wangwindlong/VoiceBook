package us.wangxy.voicebook.voice.tts

/**
 * TTS 文本前端归一化 —— 在文本送进 sherpa-onnx 合成前做本地预处理。
 *
 * 设计边界（依据 2026-09 对 matcha-icefall-zh-en 的实测）：
 *  - 中文数字/日期/小数/电话已由模型包内规则 FST（date/number/phone-zh.fst）覆盖，
 *    引擎会自动加载它们，**这里不重复处理**（重复处理会与规则冲突）；
 *  - 常用多音词已由 lexicon（6.8 万条，如「银行 yin2 hang2」「行长 hang2 zhang3」
 *    「重庆 chong2 qing4」）正确解析 —— 15 组常见多音字实测仅 3 组有差异且多为
 *    STT 反识别的同音误差，所以默认替换表刻意保持极小，避免过度替换引入新错误；
 *  - 本类只处理模型覆盖不到的两类：长数字串（手机号/卡号应逐位念）与确证的易错词。
 */
object TtsTextNormalizer {

    /**
     * 触发逐位念的最短连续数字长度。手机号 11 位、银行卡 16–19 位会命中；
     * 4 位年份与 2–4 位数量不会命中，保持模型规则 FST 的正常读法。
     */
    private const val DIGIT_RUN_MIN = 7

    /**
     * 易错词替换表（会读错的写法 → 同音替代写法）。
     * 替代字必须能在 lexicon 中查到目标读音，否则替换无效。
     * 默认只保留已确证的条目，调用方/业务方可按需追加。
     */
    private val REPLACEMENTS: List<Pair<String, String>> = listOf(
        // 姓氏「重」读 chóng，而 lexicon 仅收「重 zhong4」→ 用同音字「崇」纠正
        "姓重" to "姓崇",
        // 「开始回声」跨词边界被前端读成乱音（实测 STT 只听到「开开声」，「始回」音节丢失；
        // 同音字替换无效，插入停顿后完整可辨——2026-09 桌面 TTS→STT 闭环实测）
        "开始回声" to "开始，回声",
    )

    /** 归一化入口：先纠正易错词，再处理长数字串。 */
    fun normalize(text: String): String {
        var s = text
        for ((wrong, right) in REPLACEMENTS) s = s.replace(wrong, right)
        return spaceOutLongDigits(s)
    }

    /**
     * 长数字串逐位念：在连续数字之间插入空格。
     * 实测（matcha-zh-en）：不处理时「13800138000」被读成「一百三十八亿零一十三万八千」，
     * 加空格后正确逐位念出「一三八零零一三八零零零」。
     */
    fun spaceOutLongDigits(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9') {
                var j = i
                while (j < text.length && text[j] in '0'..'9') j++
                val run = text.substring(i, j)
                if (run.length >= DIGIT_RUN_MIN) {
                    for (k in run.indices) {
                        if (k > 0) sb.append(' ')
                        sb.append(run[k])
                    }
                } else {
                    sb.append(run)
                }
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
