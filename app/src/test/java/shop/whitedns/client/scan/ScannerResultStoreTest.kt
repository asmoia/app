package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.WhiteDnsScanState
import shop.whitedns.client.model.WhiteDnsScanStatus

class ScannerResultStoreTest {
    @Test
    fun validResultsNeverReadAnotherEnginesHistory() {
        val stores = mapOf(
            DnsClientEngine.StormDns to setOf("1.1.1.1"),
            DnsClientEngine.CottenDns to setOf("8.8.8.8:5353"),
            DnsClientEngine.Soar to setOf("9.9.9.9"),
        )
        stores.forEach { (engine, expected) ->
            val reads = mutableListOf<String>()
            val result = WhiteDnsScannerResultStore.readScopedResolverSet(engine) {
                reads += it
                stores[it].orEmpty()
            }
            assertEquals(expected, result)
            assertEquals(listOf(engine), reads)
        }
    }

    @Test
    fun anEmptyEngineStoreDoesNotPromoteForeignCandidatesToValid() {
        val result = WhiteDnsScannerResultStore.readScopedResolverSet(DnsClientEngine.CottenDns) {
            if (it == DnsClientEngine.StormDns) setOf("1.1.1.1", "8.8.8.8") else emptySet()
        }
        assertTrue(result.isEmpty())
    }

    private fun completedScan() = WhiteDnsScanState(
        sessionId = "explicit-scan", status = WhiteDnsScanStatus.Completed,
        totalResolvers = 2, completedResolvers = 2, startedAtMillis = 1234,
        validResolverEntries = listOf("1.1.1.1:53", "8.8.8.8:5353"),
    )

    @Test
    fun autoSaveUsesOnlyNewCompletedScanEntriesAndKeepsCustomPorts() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8:5353"), completedScanResolversForAutoSave(completedScan(), "previous-scan"))
    }

    @Test
    fun runtimeRefreshCannotReplayCompletedResultsOverEditedProfile() {
        assertTrue(completedScanResolversForAutoSave(completedScan(), "explicit-scan").isEmpty())
        assertTrue(completedScanResolversForAutoSave(WhiteDnsScanState(), "").isEmpty())
        assertTrue(completedScanResolversForAutoSave(completedScan().copy(startedAtMillis = 0), "").isEmpty())
    }

    @Test
    fun oneTimeMigrationDoesNotReplayLegacyCompletedScan() {
        val handled = initialHandledScanSessionId(null, completedScan())
        assertEquals("explicit-scan", handled)
        assertTrue(completedScanResolversForAutoSave(completedScan(), handled).isEmpty())
    }

    @Test
    fun backgroundCompletionAutosavesAfterActivityRestartOnlyOnce() {
        val previous = initialHandledScanSessionId(null, completedScan().copy(status = WhiteDnsScanStatus.Running))
        assertEquals("", previous)
        val onReopen = initialHandledScanSessionId(previous, completedScan())
        assertEquals(2, completedScanResolversForAutoSave(completedScan(), onReopen).size)
        val afterSaveAndRestart = initialHandledScanSessionId("explicit-scan", completedScan())
        assertTrue(completedScanResolversForAutoSave(completedScan(), afterSaveAndRestart).isEmpty())
    }

    @Test
    fun partialFailedOrPreparedScansDoNotReplaceSavedProfiles() {
        for (status in listOf(WhiteDnsScanStatus.Starting, WhiteDnsScanStatus.Running,
            WhiteDnsScanStatus.Ready, WhiteDnsScanStatus.Failed, WhiteDnsScanStatus.Stopped)) {
            assertTrue(completedScanResolversForAutoSave(completedScan().copy(status = status), "").isEmpty())
        }
        assertTrue(completedScanResolversForAutoSave(completedScan().copy(completedResolvers = 1), "").isEmpty())
    }
}
