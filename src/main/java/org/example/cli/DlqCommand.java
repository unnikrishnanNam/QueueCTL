package org.example.cli;

import org.example.core.Database;
import org.example.core.Job;
import org.example.core.JobRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "dlq", description = "Dead Letter Queue operations.", subcommands = { DlqCommand.Ls.class,
        DlqCommand.Retry.class })
public class DlqCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("dlq [list|retry <jobId>]");
    }

    @Command(name = "list", description = "View DLQ jobs.")
    static class Ls implements Runnable {
        @Override
        public void run() {
            Database.init();
            JobRepository repo = new JobRepository(2);
            java.util.List<Job> jobs = repo.listJobsByState("DEAD");
            if (jobs.isEmpty()) {
                System.out.println("DLQ is empty");
                return;
            }
            for (Job j : jobs) {
                System.out.println(j.getId() + "\tatt=" + j.getAttempts() + "/" + j.getMaxRetries() + "\tlast_error="
                        + (j.getLastError() == null ? "" : j.getLastError()));
            }
        }
    }

    @Command(name = "retry", description = "Retry a DLQ job.")
    static class Retry implements Runnable {
        @Parameters(index = "0", description = "Job ID to retry")
        String jobId;

        @Override
        public void run() {
            Database.init();
            JobRepository repo = new JobRepository(2);
            Job j = repo.getJobById(jobId);
            if (j == null || j.getState() != org.example.core.JobState.DEAD) {
                System.out.println("Job not in DLQ: " + jobId);
                return;
            }
            repo.retryDeadJob(jobId);
            System.out.println("Retried job from DLQ: " + jobId);
        }
    }
}