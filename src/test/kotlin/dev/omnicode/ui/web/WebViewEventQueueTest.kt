package dev.omnicode.ui.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebViewEventQueueTest {
    @Test
    fun `events wait for ready and are delivered in one ordered batch`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<List<String>>()
        val queue = WebViewEventQueue(
            scheduleOnUiThread = scheduled::addLast,
            deliverBatch = delivered::add,
        )

        queue.offer("one")
        queue.offer("two")
        assertTrue(scheduled.isEmpty())

        queue.markReady()
        scheduled.removeFirst().invoke()

        assertEquals(listOf(listOf("one", "two")), delivered)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `coalesces refreshes but never sheds a terminal result`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        val queue = WebViewEventQueue(
            capacity = 2,
            maxBatchMessages = 8,
            scheduleOnUiThread = scheduled::addLast,
            deliverBatch = { delivered += it },
        )

        queue.offer("running-1", "running:session")
        queue.offer("running-2", "running:session")
        queue.offer("history", "history")
        assertTrue(queue.offer("terminal", critical = true))
        assertFalse(queue.offer("ordinary-overflow"))

        queue.markReady()
        while (scheduled.isNotEmpty()) scheduled.removeFirst().invoke()

        assertEquals(listOf("history", "terminal"), delivered)
    }

    @Test
    fun `dispose rejects future messages and clears buffered data`() {
        val queue = WebViewEventQueue(
            scheduleOnUiThread = { error("must not schedule") },
            deliverBatch = { error("must not deliver") },
        )
        queue.offer("buffered")
        queue.dispose()

        assertEquals(0, queue.pendingCount())
        assertFalse(queue.offer("late", critical = true))
    }
}
