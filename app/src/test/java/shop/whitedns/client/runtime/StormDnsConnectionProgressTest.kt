package shop.whitedns.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StormDnsConnectionProgressTest {
    @Test
    fun parseStormDnsConnectionProgressLineParsesMtuProgress() {
        val state = parseStormDnsConnectionProgressLine(
            "2026 WD_PROGRESS phase=mtu percent=45 completed=27 total=54 valid=20 rejected=7",
        )

        requireNotNull(state)
        assertEquals("mtu", state.phase)
        assertEquals(45, state.percent)
        assertEquals(27, state.completed)
        assertEquals(54, state.total)
        assertEquals(20, state.valid)
        assertEquals(7, state.rejected)
        assertEquals("Scanning 27/54", state.label)
    }

    @Test
    fun parseStormDnsConnectionProgressLineInfersMtuPercentWhenMissing() {
        val state = parseStormDnsConnectionProgressLine(
            "WD_PROGRESS phase=mtu completed=27 total=54 valid=20 rejected=7",
        )

        requireNotNull(state)
        assertEquals(45, state.percent)
    }

    @Test
    fun parseStormDnsConnectionProgressLineIgnoresOtherLines() {
        assertNull(parseStormDnsConnectionProgressLine("not progress"))
    }

    @Test
    fun authenticatedReadyMarkerIsParsedWithAnsiAndEarlierPhasesStayConnecting() {
        val ready = parseStormDnsConnectionProgressLine("${27.toChar()}[32mWD_PROGRESS phase=connected percent=100${27.toChar()}[0m")
        assertEquals("connected", ready?.phase)
        assertEquals(100, ready?.percent)
        assertEquals("runtime", parseStormDnsConnectionProgressLine("WD_PROGRESS phase=runtime percent=98")?.phase)
    }

    @Test
    fun cottenMaxBootstrapStagesRemainVisibleUntilTransferIsVerified() {
        val lines = listOf(
            "WD_PROGRESS phase=starting percent=5 mode=cottenmax",
            "WD_PROGRESS phase=authenticating percent=10 mode=cottenmax attempt=1",
            "WD_PROGRESS phase=retry percent=10 mode=cottenmax stage=authenticating attempt=1",
            "WD_PROGRESS phase=authenticating percent=10 mode=cottenmax attempt=2",
            "WD_PROGRESS phase=verifying percent=90 mode=cottenmax attempt=2",
            "WD_PROGRESS phase=connected percent=100 mode=cottenmax",
        )
        val states = lines.map { requireNotNull(parseStormDnsConnectionProgressLine(it)) }

        assertEquals(listOf(5, 10, 10, 10, 90, 100), states.map { it.percent })
        assertEquals("Authenticating tunnel", states[1].label)
        assertEquals("Retrying", states[2].label)
        assertEquals("Verifying tunnel transfer", states[4].label)
        assertEquals(listOf(false, false, false, false, false, true), states.map { it.phase == "connected" })
    }

    @Test
    fun cottenMaxBootstrapStagesInferPercentWhenCoreOmitsIt() {
        assertEquals(10, parseStormDnsConnectionProgressLine("WD_PROGRESS phase=authenticating mode=cottenmax")?.percent)
        assertEquals(10, parseStormDnsConnectionProgressLine("WD_PROGRESS phase=retry mode=cottenmax attempt=2")?.percent)
        assertEquals(90, parseStormDnsConnectionProgressLine("WD_PROGRESS phase=verifying mode=cottenmax")?.percent)
    }

}
