package dev.omnicode.agent

import dev.omnicode.model.TokenUsage
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAgentBudgetLedgerTest {
    @Test
    fun `commits are separated by agent and aggregated in one snapshot`() {
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 1_000)
        val explorer = ledger.reserve("explorer-1", TokenUsage(100, 50))
        val reviewer = ledger.reserve("reviewer-1", TokenUsage(200, 50))

        ledger.commit(explorer, TokenUsage(80, 20))
        val snapshot = ledger.commit(reviewer, TokenUsage(150, 30)).snapshot

        assertEquals(TokenUsage(230, 50), snapshot.usage)
        assertEquals(TokenUsage(80, 20), snapshot.usageByAgent["explorer-1"])
        assertEquals(TokenUsage(150, 30), snapshot.usageByAgent["reviewer-1"])
        assertEquals(TokenUsage(), snapshot.reservedUsage)
        assertEquals(0, snapshot.activeReservations)
    }

    @Test
    fun `concurrent reservations cannot oversubscribe or lose committed usage`() {
        val workers = 64
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = workers * 20L)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until workers).map { index ->
            thread(start = true, name = "ledger-$index") {
                runCatching {
                    val reservation = ledger.reserve("agent-$index", TokenUsage(10, 10))
                    ledger.commit(reservation, TokenUsage(7, 3))
                }.exceptionOrNull()?.let(failures::add)
            }
        }
        threads.forEach(Thread::join)

        assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
        val snapshot = ledger.snapshot()
        assertEquals(TokenUsage(workers * 7L, workers * 3L), snapshot.usage)
        assertEquals(workers, snapshot.usageByAgent.size)
        assertEquals(0, snapshot.activeReservations)
    }

    @Test
    fun `active reservations enforce token and cost hard limits`() {
        val tokenLedger = SharedAgentBudgetLedger(maxTotalTokens = 100)
        val first = tokenLedger.reserve("agent-a", TokenUsage(60, 20))

        assertFailsWith<SharedAgentBudgetExceededException> {
            tokenLedger.reserve("agent-b", TokenUsage(10, 11))
        }
        tokenLedger.release(first)
        assertNotNull(tokenLedger.reserve("agent-b", TokenUsage(10, 11)))

        val costLedger = SharedAgentBudgetLedger(
            maxTotalTokens = 1_000,
            maxCostUsd = BigDecimal("0.50"),
            estimator = { usage -> BigDecimal.valueOf(usage.totalTokens).movePointLeft(2) },
        )
        assertFailsWith<SharedAgentBudgetExceededException> {
            costLedger.reserve("agent-a", TokenUsage(40, 11))
        }
    }

    @Test
    fun `input and output limits are enforced independently from total tokens`() {
        val inputLedger = SharedAgentBudgetLedger(
            maxTotalTokens = 1_000,
            maxInputTokens = 100,
            maxOutputTokens = 900,
        )
        assertFailsWith<SharedAgentBudgetExceededException> {
            inputLedger.reserve("agent-a", TokenUsage(101, 1))
        }

        val outputLedger = SharedAgentBudgetLedger(
            maxTotalTokens = 1_000,
            maxInputTokens = 900,
            maxOutputTokens = 100,
        )
        assertFailsWith<SharedAgentBudgetExceededException> {
            outputLedger.reserve("agent-a", TokenUsage(1, 101))
        }
    }

    @Test
    fun `cost warning is emitted once across reserve commit and release`() {
        val ledger = SharedAgentBudgetLedger(
            maxTotalTokens = 1_000,
            maxCostUsd = BigDecimal("1.00"),
            warningRatio = 0.5,
            estimator = { usage -> BigDecimal.valueOf(usage.totalTokens).movePointLeft(2) },
        )

        val first = ledger.reserve("agent-a", TokenUsage(30, 20))
        assertNotNull(first.warning)
        ledger.release(first)
        val second = ledger.reserve("agent-b", TokenUsage(30, 20))
        assertNull(second.warning)
        val update = ledger.commit(second, TokenUsage(40, 10))
        assertNull(update.warning)
        assertFalse(update.snapshot.hardLimitExceeded)
    }

    @Test
    fun `agent-aware pricing aggregates different models without charging the primary rate`() {
        val ledger = SharedAgentBudgetLedger(
            maxTotalTokens = 1_000,
            maxCostUsd = BigDecimal("10.00"),
            estimator = { BigDecimal("999.00") },
            agentEstimator = { agentId, usage ->
                val rate = if (agentId == "vision-assist") BigDecimal("0.02") else BigDecimal("0.01")
                BigDecimal.valueOf(usage.totalTokens).multiply(rate)
            },
        )
        val lead = ledger.reserve("lead", TokenUsage(10, 10))
        val vision = ledger.reserve("vision-assist", TokenUsage(5, 5))

        ledger.commit(lead, TokenUsage(10, 10))
        val snapshot = ledger.commit(vision, TokenUsage(5, 5)).snapshot

        assertEquals(BigDecimal("0.40"), snapshot.estimatedCostUsd)
    }

    @Test
    fun `release is exact and cannot be applied twice`() {
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100)
        val reservation = ledger.reserve("agent-a", TokenUsage(20, 10))

        val snapshot = ledger.release(reservation)

        assertEquals(TokenUsage(), snapshot.usage)
        assertEquals(TokenUsage(), snapshot.reservedUsage)
        assertFailsWith<IllegalStateException> { ledger.release(reservation) }
    }

    @Test
    fun `reservation cannot be committed or released by another ledger`() {
        val first = SharedAgentBudgetLedger(maxTotalTokens = 100)
        val second = SharedAgentBudgetLedger(maxTotalTokens = 100)
        val firstReservation = first.reserve("agent-a", TokenUsage(20, 10))
        second.reserve("agent-a", TokenUsage(20, 10))

        assertFailsWith<IllegalStateException> {
            second.commit(firstReservation, TokenUsage(5, 5))
        }
        assertEquals(1, first.snapshot().activeReservations)
        assertEquals(1, second.snapshot().activeReservations)
    }

    @Test
    fun `actual usage above a reservation is recorded and closes the hard limit`() {
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100)
        val reservation = ledger.reserve("agent-a", TokenUsage(20, 10))

        val update = ledger.commit(reservation, TokenUsage(80, 30))

        assertEquals(TokenUsage(80, 30), update.snapshot.usage)
        assertTrue(update.snapshot.hardLimitExceeded)
        assertFailsWith<SharedAgentBudgetExceededException> {
            ledger.reserve("agent-b", TokenUsage())
        }
    }

    @Test
    fun `overflowing committed usage saturates and permanently closes the hard limit`() {
        val ledger = SharedAgentBudgetLedger(
            maxTotalTokens = Long.MAX_VALUE,
            maxInputTokens = Long.MAX_VALUE,
            maxOutputTokens = Long.MAX_VALUE,
        )
        val first = ledger.reserve("agent-a", TokenUsage(outputTokens = 1))
        ledger.commit(first, TokenUsage(outputTokens = Long.MAX_VALUE - 5))
        val second = ledger.reserve("agent-a", TokenUsage(outputTokens = 1))

        val update = ledger.commit(second, TokenUsage(outputTokens = 10))

        assertEquals(Long.MAX_VALUE, update.snapshot.usage.outputTokens)
        assertTrue(update.snapshot.hardLimitExceeded)
        assertFailsWith<SharedAgentBudgetExceededException> {
            ledger.reserve("agent-b", TokenUsage())
        }
    }
}
