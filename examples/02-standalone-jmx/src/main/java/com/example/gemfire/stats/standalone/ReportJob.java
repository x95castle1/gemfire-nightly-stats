package com.example.gemfire.stats.standalone;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;

/** Collects metrics and writes a unique report. */
public final class ReportJob {
    @FunctionalInterface
    public interface Source {
        Map<String, Object> collect() throws Exception;
    }

    private final ReportStore store;
    private final Clock clock;

    public ReportJob(ReportStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Path collect(Source source) throws Exception {
        Map<String, Object> report = source.collect();
        return store.write(report, ZonedDateTime.now(clock));
    }
}
