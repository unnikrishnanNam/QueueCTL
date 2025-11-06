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

    @Override
    public void run() {
        // ensure DB initialized
        Database.init();
        ConfigRepository cfg = new ConfigRepository();
        int backoff = cfg.getInt("backoff_base", 2);
        int defaultRetries = cfg.getInt("max_retries", 3);
        JobRepository repo = new JobRepository(backoff);
        String jobId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        int mr = (maxRetries == null ? defaultRetries : maxRetries);
        Job job = new Job(jobId, command, mr, priority);
        try {
            repo.createJob(job);
            System.out.println("[enqueue] Enqueued job: " + jobId);
        } catch (Exception e) {
            System.err.println("Failed to enqueue job: " + e.getMessage());
        }
    }
}