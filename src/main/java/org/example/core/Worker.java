package org.example.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public class Worker implements Runnable {
    private final String workerId;
    private final JobRepository repo;
    private final boolean produceStdout;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final WorkerRegistry registry = new WorkerRegistry();

    public Worker(String workerId, JobRepository repo) {
        this(workerId, repo, true);
    }

    public Worker(String workerId, JobRepository repo, boolean produceStdout) {
        this.workerId = workerId;
        this.repo = repo;
        this.produceStdout = produceStdout;
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        registry.register(workerId);
        while (running.get()) {
            try {
                if (Database.isStopRequested()) {
                    // stop requested; exit when idle
                    break;
                }
                registry.heartbeat(workerId, "IDLE");
                Job job = repo.claimPendingJob(workerId);
                if (job == null) {
                    // periodic perf sample even when idle
                    WorkerPerf.sample(workerId);
                    Thread.sleep(1000);
                    continue;
                }
                registry.heartbeat(workerId, "BUSY");
                if (produceStdout)
                    System.out.println("[" + workerId + "] Picked job: " + job.getId() + " cmd=" + job.getCommand());
                long startMs = System.currentTimeMillis();
                WorkerPerf.setCurrentJob(workerId, job.getId(), startMs);
                // take an immediate sample at job start so charts update during BUSY
                WorkerPerf.sample(workerId);
                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", job.getCommand());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                Thread readerThread = Thread.ofVirtual().unstarted(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            out.append(line).append('\n');
                        }
                    } catch (Exception ignored) {
                    }
                });
                readerThread.start();
                Integer exitCode = null;
                int timeoutSec = job.getTimeoutSeconds();
                long deadlineMs = timeoutSec > 0 ? startMs + timeoutSec * 1000L : Long.MAX_VALUE;
                long lastPerfSampleMs = startMs;
                while (exitCode == null) {
                    try {
                        exitCode = p.exitValue();
                    } catch (IllegalThreadStateException itse) {
                        // still running
                    }
                    // periodically sample perf while job is running so charts update in BUSY state
                    long now = System.currentTimeMillis();
                    if (now - lastPerfSampleMs >= 1000) {
                        WorkerPerf.sample(workerId);
                        lastPerfSampleMs = now;
                    }
                    if (System.currentTimeMillis() > deadlineMs) {
                        // timeout
                        p.destroyForcibly();
                        exitCode = -999; // custom timeout code
                        out.append("\n[TIMEOUT after " + timeoutSec + "s]\n");
                        break;
                    }
                    if (exitCode == null) {
                        Thread.sleep(200);
                    }
                }
                // ensure reader finished
                try {
                    readerThread.join(1000);
                } catch (InterruptedException ignored) {
                }
                long durationSec = (System.currentTimeMillis() - startMs) / 1000;
                WorkerPerf.finishJob(workerId, System.currentTimeMillis() - startMs);
                String logEntry = String.format("[%s] job=%s state=%s attempts=%d duration=%ds\noutput:\n%s\n",
                        workerId, job.getId(),
                        (exitCode == 0 ? "COMPLETED" : (exitCode == -999 ? "TIMEOUT" : "FAILED")),
                        job.getAttempts() + 1, durationSec,
                        out.toString());
                // write to per-worker log file
                try {
                    java.nio.file.Path logFile = java.nio.file.Paths.get(Database.baseDir(), "logs", workerId + ".log");
                    java.nio.file.Files.writeString(logFile,
                            Instant.now().toString() + " " + logEntry + System.lineSeparator(),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception e) {
                    // ignore logging errors
                }

                if (exitCode == 0) {
                    repo.markJobCompleted(job.getId(), out.toString());
                    if (produceStdout)
                        System.out.println(
                                "[" + workerId + "] Completed job=" + job.getId() + " in " + durationSec + "s");
                } else {
                    String err = (exitCode == -999 ? "timeout after " + timeoutSec + "s" : ("exit=" + exitCode))
                            + "; output=" + out.toString();
                    repo.handleFailedAttempt(job, err);
                    if (produceStdout)
                        System.out.println("[" + workerId + "] Failed job=" + job.getId()
                                + (exitCode == -999 ? " (TIMEOUT)" : " exit=" + exitCode));
                }
                registry.heartbeat(workerId, "IDLE");
                WorkerPerf.sample(workerId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Worker error: " + e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
        System.out.println("[" + workerId + "] Stopped");
        registry.markStopped(workerId);
    }
}
