package org.example.cli;

import org.example.core.Database;
import org.example.core.JobRepository;
import org.example.core.WorkerRegistry;
import picocli.CommandLine.Command;

import java.util.Map;

@Command(name = "status", description = "Show summary of all job states & workers (busy and idle).")
public class StatusCommand implements Runnable {
    @Override
    public void run() {
        Database.init();
        JobRepository repo = new JobRepository(2);
        Map<String, Integer> m = repo.stateCounts();
        System.out.println("Jobs:");
        System.out.println("  PENDING    : " + m.getOrDefault("PENDING", 0));
        System.out.println("  PROCESSING : " + m.getOrDefault("PROCESSING", 0));
        System.out.println("  COMPLETED  : " + m.getOrDefault("COMPLETED", 0));
        System.out.println("  FAILED     : " + m.getOrDefault("FAILED", 0));
        System.out.println("  DEAD       : " + m.getOrDefault("DEAD", 0));

        WorkerRegistry wr = new WorkerRegistry();
        WorkerRegistry.Counts counts = wr.counts();
        System.out.println("Workers:");
        System.out.println("  IDLE : " + counts.idle());
        System.out.println("  BUSY : " + counts.busy());
        System.out.println("  (Use 'queuectl list --state PROCESSING' to see busy jobs)");
    }
}