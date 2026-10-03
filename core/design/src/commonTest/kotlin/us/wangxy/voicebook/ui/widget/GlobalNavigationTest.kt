package us.wangxy.voicebook.ui.widget

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.*

class GlobalNavigationTest {
    @Test fun userScrollChangesVisibilityWithoutConsumingContentScroll() {
        val state = GlobalUiState()
        assertEquals(Offset.Zero, state.scrollConnection.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput))
        assertFalse(state.navigationVisible)
        state.scrollConnection.onPreScroll(Offset(0f, 40f), NestedScrollSource.SideEffect)
        assertFalse(state.navigationVisible)
        state.scrollConnection.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)
        assertTrue(state.navigationVisible)
    }
    @Test fun smallDirectionChangesDoNotFlicker() {
        val state = GlobalUiState()
        repeat(10) {
            state.scrollConnection.onPreScroll(Offset(0f, -5f), NestedScrollSource.UserInput)
            state.scrollConnection.onPreScroll(Offset(0f, 5f), NestedScrollSource.UserInput)
        }
        assertTrue(state.navigationVisible)
    }
}
