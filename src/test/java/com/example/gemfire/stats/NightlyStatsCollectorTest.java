package com.example.gemfire.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NightlyStatsCollectorTest {

    @Test
    void findsTheInstallationPathFromGemFiresOwnJar() {
        // A gfsh-started 10.1 server
        assertEquals("/opt/gemfire/vmware-gemfire-10.1.3", NightlyStatsCollector.installationPath(
                "/opt/gemfire/vmware-gemfire-10.1.3/lib/gemfire-bootstrap-10.1.3.jar"));
        // A gfsh-started 10.3 server
        assertEquals("/opt/gemfire/vmware-gemfire-10.3.2", NightlyStatsCollector.installationPath(
                "/opt/gemfire/vmware-gemfire-10.3.2/lib/gemfire-bootstrap.jar"));
        // The collector's locator, with another app's lib/ folder first
        assertEquals("/opt/gemfire/vmware-gemfire-10.1.3", NightlyStatsCollector.installationPath(
                "/opt/app/lib/app.jar:/opt/gemfire/vmware-gemfire-10.1.3/lib/gemfire-dependencies.jar"));
        assertNull(NightlyStatsCollector.installationPath("/opt/app/lib/app.jar"));
    }

    @Test
    void writesUnknownPhysicalMemoryAsNull() {
        assertEquals(38654705664L, NightlyStatsCollector.physicalMemory(38654705664L));
        assertNull(NightlyStatsCollector.physicalMemory(-1L));
        assertNull(NightlyStatsCollector.physicalMemory(null));
    }
}
