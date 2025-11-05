package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "dlq", description = "Dead Letter Queue operations.", subcommands = { DlqCommand.List.class,
        DlqCommand.Retry.class })
public class DlqCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("dlq [list|retry <jobId>]");
    }

    @Command(name = "list", description = "View DLQ jobs.")
    static class List implements Runnable {
        @Override
        public void run() {
            System.out.println("[dlq list] View DLQ jobs");
        }
    }

    @Command(name = "retry", description = "Retry a DLQ job.")
    static class Retry implements Runnable {
        @Parameters(index = "0", description = "Job ID to retry")
        String jobId;

        @Override
        public void run() {
            System.out.println("[dlq retry] Retry job: " + jobId);
        }
    }
}