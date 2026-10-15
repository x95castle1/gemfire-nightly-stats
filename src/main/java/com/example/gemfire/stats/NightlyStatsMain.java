package com.example.gemfire.stats;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Standalone, one-shot collector and offline report tools. See standalone.properties.example. */
public final class NightlyStatsMain {
    static final int OK = 0;
    static final int CONFIG_ERROR = 2;
    static final int COLLECTION_ERROR = 3;
    static final int DELIVERY_ERROR = 4;

    @FunctionalInterface
    interface Source {
        Map<String, Object> collect(StatsSettings settings) throws Exception;
    }

    private NightlyStatsMain() {}

    public static void main(String... args) {
        // RMI transport threads can outlive a failed/closed connection. Always finish this one-shot process.
        System.exit(run(args, System.out, System.err, RemoteJmx::collect));
    }

    static int run(String[] args, PrintStream out, PrintStream err, Source source) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            out.println("Usage: collect-stats.sh <collect|status|upload-pending|export> --config <file>"
                    + " [export: --output <archive.zip> [--from yyyy-MM-dd] [--to yyyy-MM-dd]]");
            return args.length == 0 ? CONFIG_ERROR : OK;
        }
        try {
            String command = args[0];
            if (!Set.of("collect", "status", "upload-pending", "export").contains(command)) {
                throw new IllegalArgumentException("Unknown command: " + command);
            }
            Map<String, String> options = options(args, command.equals("export"));
            Properties properties = new Properties();
            try (var input = Files.newInputStream(Path.of(requiredOption(options, "--config")))) {
                properties.load(input);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read --config: " + e.getMessage(), e);
            }
            StatsSettings settings = new StatsSettings(properties);
            String env = StatsSettings.segment("ENV", settings.required("ENV"));
            String cluster = StatsSettings.segment("CLUSTER_NAME", settings.required("CLUSTER_NAME"));
            ReportStore store = new ReportStore(Path.of(settings.get("REPORT_DIR", "reports")).resolve(env).resolve(cluster), cluster);
            String mode = settings.mode("local");
            boolean required = settings.bool("UPLOAD_REQUIRED", false);
            if (required && mode.equals("local")) {
                throw new IllegalArgumentException("UPLOAD_REQUIRED=true requires DELIVERY_MODE=s3");
            }
            Clock clock = Clock.system(ZoneId.of(settings.get("REPORT_TIMEZONE", ZoneId.systemDefault().getId())));
            if (command.equals("collect")) RemoteJmx.validate(settings);
            ReportDelivery delivery = command.equals("export") ? null : settings.delivery(store.directory, env, cluster,
                    "local", command.equals("collect") || command.equals("upload-pending"));
            if (command.equals("upload-pending") && delivery == null) {
                throw new IllegalArgumentException("upload-pending requires DELIVERY_MODE=s3");
            }
            LocalDate from = LocalDate.MIN;
            LocalDate to = LocalDate.MAX;
            Path archive = null;
            if (command.equals("export")) {
                archive = Path.of(requiredOption(options, "--output"));
                from = LocalDate.parse(options.getOrDefault("--from", LocalDate.MIN.toString()));
                to = LocalDate.parse(options.getOrDefault("--to", LocalDate.MAX.toString()));
                if (from.isAfter(to)) throw new IllegalArgumentException("--from must be on or before --to");
            }
            try (var ignored = store.lock()) {
                if (command.equals("export")) {
                    int count = store.export(archive, env, from, to);
                    out.print(Json.write(Map.of("archive", archive.toAbsolutePath().toString(), "reports", count)));
                    return OK;
                }
                if (command.equals("status")) {
                    out.print(Json.write(status(store, delivery, env, mode)));
                    return OK;
                }
                return execute(command, settings, store, delivery, env, mode, clock, required, source, out, err);
            }
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            err.println("Configuration error: " + failureMessage(e));
            return CONFIG_ERROR;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            err.println("Command failed: " + failureMessage(e));
            return COLLECTION_ERROR;
        }
    }

    private static int execute(String command, StatsSettings settings, ReportStore store, ReportDelivery delivery,
            String env, String mode, Clock clock, boolean required, Source source, PrintStream out, PrintStream err) throws IOException {
        int result = OK;
        Path report = null;
        if (command.equals("collect")) {
            long minimum = settings.positiveInt("MIN_FREE_SPACE_MB", 100) * 1024L * 1024L;
            if (Files.getFileStore(store.directory).getUsableSpace() < minimum) {
                err.println("Warning: report storage has less than " + minimum + " bytes free");
            }
            try {
                report = new ReportJob(store, clock).collect(() -> source.collect(settings));
            } catch (Exception e) {
                err.println("Collection/local write failed: " + failureMessage(e));
                result = COLLECTION_ERROR;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        int uploaded = 0;
        int pendingBefore = delivery == null ? 0 : delivery.pendingReports().size();
        String uploadError = null;
        if (delivery != null) {
            try {
                uploaded = delivery.uploadPending();
            } catch (Exception e) {
                uploadError = failureMessage(e);
                err.println("Upload deferred; reports remain on disk: " + uploadError);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> summary = status(store, delivery, env, mode);
        if (delivery != null) uploaded = pendingBefore - ((Number) summary.get("pending_uploads")).intValue();
        summary.put("uploaded", uploaded);
        summary.put("upload_error", uploadError);
        if (command.equals("collect")) {
            summary.put("collection_succeeded", report != null);
            summary.put("report_file", report == null ? null : report.toAbsolutePath().toString());
        }
        boolean pending = delivery != null && ((Number) summary.get("pending_uploads")).intValue() > 0;
        if (pending) err.println("Pending uploads: " + summary.get("pending_uploads")
                + "; oldest: " + summary.get("oldest_pending_date"));
        if (result == OK && (required || command.equals("upload-pending")) && (uploadError != null || pending)) {
            result = DELIVERY_ERROR;
        }
        out.print(Json.write(summary));
        return result;
    }

    private static Map<String, Object> status(ReportStore store, ReportDelivery delivery, String env, String mode) throws IOException {
        List<Path> reports = store.reports();
        List<Path> pending = delivery == null ? List.of() : delivery.pendingReports();
        long bytes = 0;
        for (Path report : reports) bytes += Files.size(report);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("env", env);
        status.put("cluster_name", store.cluster);
        status.put("delivery_mode", mode);
        status.put("report_directory", store.directory.toAbsolutePath().toString());
        status.put("saved_reports", reports.size());
        status.put("report_bytes", bytes);
        status.put("oldest_report_date", reports.isEmpty() ? null : store.date(reports.get(reports.size() - 1)).toString());
        status.put("newest_report_date", reports.isEmpty() ? null : store.date(reports.get(0)).toString());
        status.put("usable_space_bytes", Files.getFileStore(store.directory).getUsableSpace());
        status.put("upload_destination", delivery == null ? null : delivery.location());
        status.put("pending_uploads", delivery == null ? null : pending.size());
        status.put("oldest_pending_date", pending.isEmpty() ? null : store.date(pending.get(pending.size() - 1)).toString());
        return status;
    }

    private static Map<String, String> options(String[] args, boolean exporting) {
        Set<String> allowed = exporting ? Set.of("--config", "--output", "--from", "--to") : Set.of("--config");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || i + 1 == args.length || args[i + 1].startsWith("--")
                    || result.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown, duplicate or incomplete option: " + args[i]);
            }
        }
        return result;
    }

    private static String requiredOption(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static String failureMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
