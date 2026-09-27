package us.wangxy.voicebook.theme

/**
 * 当前内容（书 / 文章）的氛围色状态：screens 调 [update] 传入 seed，
 * [VoiceBookTheme] 在 AppSkin.Dynamic 下读取动画后的配色。
 * 一次 update = 一次 600ms 逐角色 lerp 过渡（Twine DynamicColorState 语义）。
 */
class SeedColorState {
    val animator = SeedSchemeAnimator()

    fun update(color: Int?) {
        animator.animateTo(color)
    }

    fun reset() = animator.reset()
}
