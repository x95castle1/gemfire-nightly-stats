package com.example.gemfire.stats;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/** The same collection/write operation is used inside a locator and by the standalone command. */
final class ReportJob {
    @FunctionalInterface
    interface Source {
        Map<String, Object> collect() throws Exception;
    }

    private final ReportStore store;
    private final Clock clock;

    ReportJob(ReportStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    Path collect(Source source) throws Exception {
        Map<String, Object> report = source.collect();
        return store.write(report, LocalDate.now(clock));
    }
}
