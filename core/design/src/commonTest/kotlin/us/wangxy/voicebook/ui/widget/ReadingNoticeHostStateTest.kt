package us.wangxy.voicebook.ui.widget

import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadingNoticeHostStateTest {
    @Test
    fun duplicateVisibleNoticeIsNotQueuedAgain() = runTest {
        val state = ReadingNoticeHostState(backgroundScope)
        val first = async { state.show("云端已读至第 9 章", "跳转") }
        runCurrent()
        val displayed = state.snackbar.currentSnackbarData
        val duplicate = async { state.show("云端已读至第 9 章", "跳转") }
        runCurrent()
        assertSame(displayed, state.snackbar.currentSnackbarData)
        state.dismiss()
        assertEquals(SnackbarResult.Dismissed, first.await())
        assertEquals(SnackbarResult.Dismissed, duplicate.await())
        runCurrent()
        assertNull(state.snackbar.currentSnackbarData)
    }

    @Test
    fun restartedCallerReusesVisibleNoticeAndCanStillJump() = runTest {
        val state = ReadingNoticeHostState(backgroundScope)
        val first = async { state.show("云端已读至第 9 章", "跳转") }
        runCurrent()
        val displayed = state.snackbar.currentSnackbarData!!
        first.cancel()
        runCurrent()
        val resumed = async { state.show("云端已读至第 9 章", "跳转") }
        runCurrent()
        assertSame(displayed, state.snackbar.currentSnackbarData)
        displayed.performAction()
        assertEquals(SnackbarResult.ActionPerformed, resumed.await())
        runCurrent()
        assertNull(state.snackbar.currentSnackbarData)
    }

    @Test
    fun duplicateQueuedNoticeIsDisplayedOnlyOnce() = runTest {
        val state = ReadingNoticeHostState(backgroundScope)
        val first = async { state.show("第一条提醒") }
        runCurrent()
        val next = async { state.show("第二条提醒") }
        val duplicate = async { state.show("第二条提醒") }
        runCurrent()
        assertEquals("第一条提醒", state.snackbar.currentSnackbarData!!.visuals.message)
        state.dismiss()
        first.await()
        runCurrent()
        assertEquals("第二条提醒", state.snackbar.currentSnackbarData!!.visuals.message)
        state.dismiss()
        next.await()
        duplicate.await()
        runCurrent()
        assertNull(state.snackbar.currentSnackbarData)
    }
}
