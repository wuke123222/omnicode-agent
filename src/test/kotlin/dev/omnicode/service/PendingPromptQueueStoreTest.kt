package dev.omnicode.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingPromptQueueStoreTest {
    private fun prompt(id: String, text: String = id, reattach: Boolean = false) =
        PendingPromptRecord(id, text, "AGENT", "AUTO", reattach)

    @Test
    fun `renderer recovery retains order and consume only removes accepted prompt`() {
        val store = PendingPromptQueueStore()
        assertTrue(store.upsert("one", prompt("client-a")))
        assertTrue(store.upsert("one", prompt("client-b", reattach = true)))
        assertTrue(store.upsert("two", prompt("client-c")))
        store.move("one", "client-b", -1)
        assertEquals(listOf("client-b", "client-a"), store.snapshot("one").map { it.clientMessageId })
        assertTrue(store.snapshot("one").first().requiresReattach)
        store.consume("one", "client-a")
        assertEquals(listOf("client-b"), store.snapshot("one").map { it.clientMessageId })
        assertFalse(store.upsert("one", prompt("client-a")), "late renderer command must not replay an accepted prompt")
        assertEquals(listOf("client-c"), store.snapshot("two").map { it.clientMessageId })
        store.clear("one")
        assertTrue(store.snapshot("one").isEmpty())
    }

    @Test
    fun `bounded queue rejects overflow without dropping an existing prompt`() {
        val store = PendingPromptQueueStore()
        repeat(20) { assertTrue(store.upsert("one", prompt("client-$it"))) }
        assertFalse(store.upsert("one", prompt("client-overflow")))
        assertEquals(20, store.snapshot("one").size)
        assertTrue(store.upsert("one", prompt("client-0", "edited")))
        assertEquals("edited", store.snapshot("one").first().text)
    }
}
