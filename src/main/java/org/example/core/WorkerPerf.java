package org.example.core;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory performance/telemetry tracking for workers.
 * Lightweight ring buffers keep recent samples (heap usage, job durations).
 */
public class WorkerPerf {
    public static final int MAX_SAMPLES = 30; // keep last 30 samples (~1 minute at 2s interval)
    private static final Map<String, Metrics> METRICS = new ConcurrentHashMap<>();

    public static Metrics metrics(String workerId) {
        return METRICS.computeIfAbsent(workerId, id -> new Metrics());
    }

    public static void setCurrentJob(String workerId, String jobId, long startMs) {
        Metrics m = metrics(workerId);
        m.currentJobId = jobId;
        m.currentJobStartMs = startMs;
        // persist current job state to workers table
        try (Connection c = Database.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE workers SET current_job_id=?, current_job_start_ms=? WHERE worker_id=?")) {
            ps.setString(1, jobId);
            ps.setLong(2, startMs);
            ps.setString(3, workerId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public static void finishJob(String workerId, long durationMs) {
        Metrics m = metrics(workerId);
        m.lastFinishedMs = System.currentTimeMillis();
        m.addJobDuration(durationMs);
        m.currentJobId = null;
        m.currentJobStartMs = 0;
        // update workers table and record a perf sample that includes the job duration
        try (Connection c = Database.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE workers SET last_finished_ms=?, current_job_id=NULL, current_job_start_ms=NULL WHERE worker_id=?")) {
            ps.setLong(1, m.lastFinishedMs);
            ps.setString(2, workerId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }

        // capture a sample with duration at finish time
        persistSample(workerId, durationMs);
    }

    /** Sample heap + process cpu load and append to history for this worker. */
    public static void sample(String workerId) {
        // capture metrics and persist a sample without job duration
        persistSample(workerId, null);
    }

    private static void persistSample(String workerId, Long lastJobDurationMs) {
        Metrics m = metrics(workerId);
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        double load;
        try {
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory
                    .getOperatingSystemMXBean();
            load = os.getProcessCpuLoad(); // 0..1 or negative if undefined
        } catch (Throwable t) {
            load = -1;
        }
        m.addHeapSample(heapUsed);
        m.addCpuSample(load);

        // persist sample
        try (Connection c = Database.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO worker_perf(worker_id, ts_ms, heap_used_bytes, cpu_load, last_job_duration_ms) VALUES(?,?,?,?,?)")) {
            ps.setString(1, workerId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, heapUsed);
            ps.setDouble(4, load);
            if (lastJobDurationMs == null)
                ps.setNull(5, java.sql.Types.INTEGER);
            else
                ps.setLong(5, lastJobDurationMs);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }

        // prune older samples, keep last MAX_SAMPLES per worker
        try (Connection c = Database.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM worker_perf WHERE worker_id=? AND id NOT IN (SELECT id FROM worker_perf WHERE worker_id=? ORDER BY id DESC LIMIT ? )")) {
            ps.setString(1, workerId);
            ps.setString(2, workerId);
            ps.setInt(3, MAX_SAMPLES);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public static List<MetricsSnapshot> snapshots() {
        List<MetricsSnapshot> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Metrics> e : METRICS.entrySet()) {
            Metrics m = e.getValue();
            long elapsed = (m.currentJobId == null || m.currentJobStartMs == 0) ? 0 : (now - m.currentJobStartMs);
            list.add(new MetricsSnapshot(e.getKey(), m.currentJobId, m.currentJobStartMs, elapsed, m.lastFinishedMs,
                    List.copyOf(m.heapUsedHistory), List.copyOf(m.jobDurationHistory), List.copyOf(m.cpuHistory)));
        }
        return list;
    }

    /** Holder for mutable metrics. */
    public static class Metrics {
        volatile String currentJobId;
        volatile long currentJobStartMs;
        volatile long lastFinishedMs;
        final List<Long> heapUsedHistory = Collections.synchronizedList(new ArrayList<>());
        final List<Long> jobDurationHistory = Collections.synchronizedList(new ArrayList<>());
        final List<Double> cpuHistory = Collections.synchronizedList(new ArrayList<>());

        void addHeapSample(long v) {
            addTo(heapUsedHistory, v);
        }

        void addJobDuration(long ms) {
            addTo(jobDurationHistory, ms);
        }

        void addCpuSample(double v) {
            addTo(cpuHistory, v);
        }

        private <T> void addTo(List<T> list, T v) {
            list.add(v);
            int overflow = list.size() - MAX_SAMPLES;
            if (overflow > 0) {
                list.subList(0, overflow).clear();
            }
        }
    }

    /** Immutable view for JSON serialization */
    public static class MetricsSnapshot {
        public final String workerId;
        public final String currentJobId;
        public final long currentJobStartMs;
        public final long currentJobElapsedMs;
        public final long lastFinishedMs;
        public final List<Long> heapUsedHistory;
        public final List<Long> jobDurationHistory; // ms durations of recent completed jobs
        public final List<Double> cpuHistory; // process CPU load samples (0..1, -1 if unavailable)

        MetricsSnapshot(String workerId, String currentJobId, long currentJobStartMs, long currentJobElapsedMs,
                long lastFinishedMs, List<Long> heapUsedHistory, List<Long> jobDurationHistory,
                List<Double> cpuHistory) {
            this.workerId = workerId;
            this.currentJobId = currentJobId;
            this.currentJobStartMs = currentJobStartMs;
            this.currentJobElapsedMs = currentJobElapsedMs;
            this.lastFinishedMs = lastFinishedMs;
            this.heapUsedHistory = heapUsedHistory;
            this.jobDurationHistory = jobDurationHistory;
            this.cpuHistory = cpuHistory;
        }
    }
}
