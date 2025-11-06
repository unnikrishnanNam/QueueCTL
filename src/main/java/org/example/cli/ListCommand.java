package org.example.cli;

import org.example.core.Database;
import org.example.core.Job;
import org.example.core.JobRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

@Command(name = "list", description = "List jobs by state.")
public class ListCommand implements Runnable {
    @Option(names = "--state", description = "Job state to filter (PENDING, PROCESSING, COMPLETED, FAILED, DEAD)")
    String state;

    @Override
    public void run() {
        Database.init();
        JobRepository repo = new JobRepository(2);
        List<Job> jobs = repo.listJobsByState(state == null ? null : state.toUpperCase());
        if (jobs.isEmpty()) {
            System.out.println("No jobs found" + (state != null ? " for state " + state : ""));
            return;
        }
        for (Job j : jobs) {
            System.out.println(j.getId() + "\t" + j.getState() + "\tatt=" + j.getAttempts() + "/" + j.getMaxRetries()
                    + "\tprio=" + j.getPriority() + "\tcmd='" + j.getCommand() + "'");
        }
    }
}