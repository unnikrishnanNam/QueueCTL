package org.example.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public class Worker implements Runnable {
    private final String workerId;
    private final JobRepository repo;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public Worker(String workerId, JobRepository repo) {
        this.workerId = workerId;
        this.repo = repo;
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                if (Database.isStopRequested()) {
                    // stop requested; exit when idle
                    break;
                }
                Job job = repo.claimPendingJob(workerId);
                if (job == null) {
                    Thread.sleep(1000);
                    continue;
                }
                System.out.println("[" + workerId + "] Picked job: " + job.getId() + " cmd=" + job.getCommand());
                long start = Instant.now().getEpochSecond();
                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", job.getCommand());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                }
                int exit = p.waitFor();
                long duration = Instant.now().getEpochSecond() - start;
                if (exit == 0) {
                    repo.markJobCompleted(job.getId(), out.toString());
                    System.out.println("[" + workerId + "] Completed job=" + job.getId() + " in " + duration + "s");
                } else {
                    String err = "exit=" + exit + "; output=" + out.toString();
                    repo.handleFailedAttempt(job, err);
                    System.out.println("[" + workerId + "] Failed job=" + job.getId() + " exit=" + exit);
                }
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
    }
}
