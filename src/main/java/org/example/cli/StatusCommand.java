package org.example.cli;

import org.example.core.Database;
import org.example.core.JobRepository;
import picocli.CommandLine.Command;

import java.util.Map;

@Command(name = "status", description = "Show summary of all job states & active workers.")
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
        System.out.println("Active workers (busy): " + repo.activeWorkerCount());
    }
}