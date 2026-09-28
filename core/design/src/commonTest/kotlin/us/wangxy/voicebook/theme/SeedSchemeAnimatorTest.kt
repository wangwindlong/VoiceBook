package us.wangxy.voicebook.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class SeedSchemeAnimatorTest {
    @Test
    fun testAnimateToWithoutMonotonicFrameClock() = runTest {
        // Scope without MonotonicFrameClock
        val customScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val animator = SeedSchemeAnimator(scope = customScope)
        animator.animateTo(0xFF0000)
        animator.animateTo(0x00FF00)
        delay(800.milliseconds)
    }
}
