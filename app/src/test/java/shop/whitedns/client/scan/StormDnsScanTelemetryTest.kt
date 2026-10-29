package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StormDnsScanTelemetryTest {
    @Test
    fun parseStormDnsScanLineParsesValidResolver() {
        val telemetry = parseStormDnsScanLine("2026 WD_SCAN event=valid resolver=1.1.1.1:53")

        assertEquals(StormDnsScanTelemetry.Valid("1.1.1.1:53"), telemetry)
    }

    @Test
    fun parseStormDnsScanLineParsesRejectedResolver() {
        val telemetry = parseStormDnsScanLine("2026 WD_SCAN event=rejected resolver=8.8.8.8:53")

        assertEquals(StormDnsScanTelemetry.Rejected("8.8.8.8:53"), telemetry)
    }

    @Test
    fun parseStormDnsScanLineParsesCompletion() {
        val telemetry = parseStormDnsScanLine("WD_SCAN event=complete total=10 valid=3 rejected=7")

        assertEquals(StormDnsScanTelemetry.Complete(total = 10, valid = 3, rejected = 7), telemetry)
    }

    @Test
    fun parseStormDnsScanLineIgnoresOtherLines() {
        assertNull(parseStormDnsScanLine("WD_PROGRESS phase=mtu percent=50"))
    }

    @Test
    fun livePathsDistinguishUdpAndTcpAndRetainOnlyVerifiedPaths() {
        val paths = StormDnsScanPaths()
        fun record(line: String) { paths.update(requireNotNull(parseStormDnsScanLine(line))) }
        record("WD_SCAN event=testing resolver=1.1.1.1:53 transport=udp")
        record("WD_SCAN event=testing resolver=1.1.1.1:53 transport=tcp")
        assertEquals(2, paths.testingPaths.size)
        record("WD_SCAN event=path resolver=1.1.1.1:53 transport=udp usable=false")
        record("WD_SCAN event=path resolver=1.1.1.1:53 transport=tcp transfer_verified=true")
        assertEquals(emptyList<String>(), paths.testingPaths)
        assertEquals(listOf("1.1.1.1:53 (TCP)"), paths.workingPaths)
    }

}
