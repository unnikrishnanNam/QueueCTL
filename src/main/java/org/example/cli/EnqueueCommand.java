package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "enqueue", description = "Add a new job to the queue.")
public class EnqueueCommand implements Runnable {
    @Option(names = "--id", required = true, description = "Job ID")
    String id;

    @Option(names = "--command", required = true, description = "Command to run")
    String command;

    @Option(names = "--max_retries", description = "Maximum retries", defaultValue = "3")
    Integer maxRetries;

    @Option(names = "--priority", description = "Job priority", defaultValue = "1")
    Integer priority;

    @Override
    public void run() {
        System.out.println("[enqueue] Add job: id=" + id + ", command='" + command + "', max_retries=" + maxRetries
                + ", priority=" + priority);
    }
}