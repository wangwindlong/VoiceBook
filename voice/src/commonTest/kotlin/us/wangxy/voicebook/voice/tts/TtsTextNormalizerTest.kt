package us.wangxy.voicebook.voice.tts

import kotlin.test.Test
import kotlin.test.assertEquals

/** TtsTextNormalizer 单测：边界必须精确，否则会与模型自带规则 FST 冲突。 */
class TtsTextNormalizerTest {

    @Test
    fun longDigitRunsAreSpacedOut() {
        assertEquals(
            "我的电话号码是1 3 8 0 0 1 3 8 0 0 0。",
            TtsTextNormalizer.normalize("我的电话号码是13800138000。"),
        )
    }

    @Test
    fun shortNumbersAreLeftToModelRuleFsts() {
        // 年份/日期/小数由模型包的 date-zh.fst、number-zh.fst 处理，前端不得插入空格
        assertEquals("请在2026年3月15日联系我。", TtsTextNormalizer.normalize("请在2026年3月15日联系我。"))
        assertEquals("圆周率是3.14。", TtsTextNormalizer.normalize("圆周率是3.14。"))
        assertEquals("温度是零下5度", TtsTextNormalizer.normalize("温度是零下5度"))
    }

    @Test
    fun boundaryIsSevenDigits() {
        assertEquals("123456", TtsTextNormalizer.normalize("123456"))          // 6 位：不动
        assertEquals("1 2 3 4 5 6 7", TtsTextNormalizer.normalize("1234567"))  // 7 位：逐位念
    }

    @Test
    fun surnameZhongIsReplaced() {
        assertEquals("行长姓崇", TtsTextNormalizer.normalize("行长姓重"))
    }

    @Test
    fun plainTextIsUntouched() {
        val t = "我在用 Kotlin Multiplatform 开发一个语音助手 App。"
        assertEquals(t, TtsTextNormalizer.normalize(t))
    }

    @Test
    fun digitRunInsideSentenceDoesNotBreakSurroundingText() {
        val got = TtsTextNormalizer.normalize("订单号20260925001已发出，金额500元。")
        assertEquals("订单号2 0 2 6 0 9 2 5 0 0 1已发出，金额500元。", got)
    }
}
