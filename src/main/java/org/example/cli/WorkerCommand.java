package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.example.core.Database;
import org.example.core.JobRepository;
import org.example.core.Worker;
import org.example.core.ConfigRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Command(name = "worker", description = "Worker management.", subcommands = { WorkerCommand.Start.class,
        WorkerCommand.Stop.class })
public class WorkerCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("worker [start|stop]");
    }

    @Command(name = "start", description = "Start one or more workers.")
    static class Start implements Runnable {
        @Option(names = "--count", description = "Number of workers to start", defaultValue = "1")
        int count;

        @Override
        public void run() {
            Database.init();
            // clear any previous stop request
            org.example.core.Database.clearStop();
            ConfigRepository cfg = new ConfigRepository();
            int backoff = cfg.getInt("backoff_base", 2);
            JobRepository repo = new JobRepository(backoff);
            List<Thread> threads = new ArrayList<>();
            List<Worker> workers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String wid = "worker-" + UUID.randomUUID().toString().substring(0, 8);
                Worker w = new Worker(wid, repo);
                Thread t = new Thread(w, wid);
                threads.add(t);
                workers.add(w);
                t.start();
                System.out.println("Started " + wid);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutdown requested, stopping workers...");
                for (Worker w : workers)
                    w.stop();
                for (Thread t : threads) {
                    try {
                        t.join(5000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }));

            // block main thread until interrupted
            try {
                for (Thread t : threads)
                    t.join();
            } catch (InterruptedException e) {
                System.out.println("Interrupted, stopping workers...");
                for (Worker w : workers)
                    w.stop();
            }
        }
    }

    @Command(name = "stop", description = "Stop running workers gracefully.")
    static class Stop implements Runnable {
        @Override
        public void run() {
            Database.init();
            org.example.core.Database.requestStop();
            System.out.println("Stop requested. Running workers will finish current job and exit.");
        }
    }
}