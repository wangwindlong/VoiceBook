package us.wangxy.voicebook.screens.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class OrderRepliesTest {

    private fun row(id: Long, rid: Long? = null, nick: String = "u$id") = CommentRow(
        id = id,
        nick = nick,
        avatarUrl = null,
        content = "c$id",
        date = "",
        liked = false,
        votes = 0,
        extras = CommentExtras(0, 0, 0),
        replyTo = rid,
    )

    @Test
    fun nestsRepliesUnderTheirParentInDepthFirstOrder() {
        // Artalk 非 flat 模式：顶层在前，回复按 rid 追加在后面
        val flat = listOf(row(1), row(2), row(3, rid = 1), row(4, rid = 1), row(5, rid = 3))

        val ordered = orderReplies(flat)

        assertEquals(listOf(1L, 3L, 5L, 4L, 2L), ordered.map { it.id })
        assertEquals(listOf(0, 1, 2, 1, 0), ordered.map { it.depth })
        val reply3 = ordered.first { it.id == 3L }
        assertEquals("u1", reply3.replyToNick)
        val reply5 = ordered.first { it.id == 5L }
        assertEquals("u3", reply5.replyToNick)
    }

    @Test
    fun orphanReplyWhoseParentIsNotOnPageBecomesTopLevel() {
        // 翻页截断：父评论 99 不在本页，回复 7 只能当顶层展示
        val flat = listOf(row(7, rid = 99))

        val ordered = orderReplies(flat)

        assertEquals(listOf(7L), ordered.map { it.id })
        assertEquals(0, ordered.single().depth)
        assertEquals(null, ordered.single().replyToNick)
    }
}
