package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.example.core.Database;
import org.example.core.Job;
import org.example.core.JobRepository;
import org.example.core.ConfigRepository;

import java.util.UUID;

@Command(name = "enqueue", description = "Add a new job to the queue.")
public class EnqueueCommand implements Runnable {
    @Option(names = "--id", description = "Job ID")
    String id;

    @Option(names = "--command", required = true, description = "Command to run")
    String command;

    @Option(names = "--max_retries", description = "Maximum retries")
    Integer maxRetries;

    @Option(names = "--priority", description = "Job priority", defaultValue = "1")
    Integer priority;

    @Option(names = "--timeout", description = "Timeout seconds (0 = none)")
    Integer timeoutSeconds;

    @Option(names = "--run_at", description = "Schedule run time (epoch seconds or ISO-8601 e.g. 2024-01-01T12:00:00Z or relative +30s,+5m,+2h)")
    String runAtSpec;

    @Override
    public void run() {
        // ensure DB initialized
        Database.init();
        ConfigRepository configRepository = new ConfigRepository();
        int backoff = configRepository.getInt("backoff_base", 2);
        int defaultRetries = configRepository.getInt("max_retries", 3);
        int defaultTimeout = configRepository.getInt("timeout_default", 0);
        int defaultPriority = configRepository.getInt("priority_default", 1);
        JobRepository repo = new JobRepository(backoff);
        String jobId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        int mr = (maxRetries == null ? defaultRetries : maxRetries);
        int prio = (priority == null ? defaultPriority : priority);
        Job job = new Job(jobId, command, mr, prio);
        // timeout
        job.setTimeoutSeconds(timeoutSeconds != null ? timeoutSeconds : defaultTimeout);
        // parse run_at schedule
        if (runAtSpec != null && !runAtSpec.isBlank()) {
            Long runAtEpoch = parseRunAt(runAtSpec);
            if (runAtEpoch != null) {
                job.setRunAtEpoch(runAtEpoch);
                job.setAvailableAtEpoch(runAtEpoch); // release only at scheduled time
            }
        }
        try {
            repo.createJob(job);
            System.out.println("[enqueue] Enqueued job: " + jobId);
        } catch (Exception e) {
            System.err.println("Failed to enqueue job: " + e.getMessage());
        }
    }

    private Long parseRunAt(String spec) {
        spec = spec.trim();
        try {
            if (spec.startsWith("+")) {
                // relative like +30s, +5m, +2h
                long now = java.time.Instant.now().getEpochSecond();
                String numPart = spec.substring(1, spec.length() - 1);
                char unit = spec.charAt(spec.length() - 1);
                long val = Long.parseLong(numPart);
                long add;
                switch (unit) {
                    case 's':
                        add = val;
                        break;
                    case 'm':
                        add = val * 60;
                        break;
                    case 'h':
                        add = val * 3600;
                        break;
                    default:
                        add = val;
                        break; // treat as seconds
                }
                return now + add;
            }
            if (spec.matches("\\d+")) {
                return Long.parseLong(spec);
            }
            // try ISO-8601
            java.time.Instant inst = java.time.Instant.parse(spec);
            return inst.getEpochSecond();
        } catch (Exception e) {
            System.err.println("Invalid --run_at value, ignoring: " + spec + " error=" + e.getMessage());
            return null;
        }
    }
}