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
import java.nio.file.Paths;

@Command(name = "worker", description = "Worker management.", subcommands = { WorkerCommand.Start.class,
        WorkerCommand.Stop.class, WorkerCommand.Daemon.class, WorkerCommand.Logs.class })
public class WorkerCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("worker [start|stop]");
    }

    @Command(name = "start", description = "Start one or more workers.")
    static class Start implements Runnable {
        @Option(names = "--count", description = "Number of workers to start", defaultValue = "1")
        int count;

        @Option(names = { "-d", "--detached" }, description = "Run workers in detached/background mode")
        boolean detached;

        @Option(names = { "-f", "--follow" }, description = "Follow worker logs in realtime (only in foreground)")
        boolean follow;

        @Option(names = { "--worker-id" }, description = "If following, only show logs for this worker id")
        String followWorkerId;

        @Override
        public void run() {
            Database.init();
            // clear any previous stop request
            org.example.core.Database.clearStop();
            ConfigRepository cfg = new ConfigRepository();
            int backoff = cfg.getInt("backoff_base", 2);
            JobRepository repo = new JobRepository(backoff);

            if (detached) {
                // try to re-launch same jar in background using nohup and capture pid
                try {
                    String jar = new java.io.File(
                            org.example.Main.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .getPath();
                    String userHome = System.getProperty("user.home");
                    String logsOut = Paths.get(Database.baseDir(), "worker.nohup.out").toString();
                    // Preserve current user.home to keep DB/logs consistent when the demo overrides
                    // it
                    String cmd = String.format(
                            "nohup java -Duser.home='%s' -jar '%s' worker start --count %d > %s 2>&1 & echo $!",
                            userHome, jar, count, logsOut);
                    ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
                    Process p = pb.start();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()))) {
                        String pid = r.readLine();
                        System.out.println("Started detached worker(s), pid=" + pid);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to start detached workers: " + e.getMessage());
                }
                return;
            }

            List<Thread> threads = new ArrayList<>();
            List<Worker> workers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String wid = "worker-" + UUID.randomUUID().toString().substring(0, 8);
                Worker w = new Worker(wid, repo, !follow);
                Thread t = Thread.ofVirtual().name(wid).start(w);
                threads.add(t);
                workers.add(w);
                System.out.println("Started " + wid);
            }

            Thread logTailerThread = null;
            org.example.core.LogTailer tailer = null;
            if (follow) {
                java.nio.file.Path logsDir = java.nio.file.Paths.get(Database.baseDir(), "logs");
                tailer = new org.example.core.LogTailer(logsDir, followWorkerId);
                logTailerThread = new Thread(tailer, "log-tailer");
                logTailerThread.setDaemon(true);
                logTailerThread.start();
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

            // stop tailer if running
            if (tailer != null)
                tailer.requestStop();
            if (logTailerThread != null) {
                try {
                    logTailerThread.join(2000);
                } catch (InterruptedException ignored) {
                }
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

    @Command(name = "daemon", description = "Manage worker daemon (start|stop|status|install-units)")
    static class Daemon implements Runnable {
        @Option(names = "--count", description = "Workers to start when starting daemon", defaultValue = "2")
        int count;
        @Option(names = "--start", description = "Start the daemon")
        boolean start;
        @Option(names = "--stop", description = "Stop the daemon")
        boolean stop;
        @Option(names = "--status", description = "Show daemon status")
        boolean status;
        @Option(names = "--install-units", description = "Generate systemd/launchd unit templates")
        boolean installUnits;

        @Override
        public void run() {
            Database.init();
            if (installUnits) {
                org.example.core.DaemonManager.installUnitTemplates();
                return;
            }
            if (start) {
                org.example.core.DaemonManager.startDaemon(count);
            } else if (stop) {
                org.example.core.DaemonManager.stopDaemon();
            } else if (status) {
                org.example.core.DaemonManager.statusDaemon();
            } else {
                System.out.println("Specify one of --start | --stop | --status | --install-units");
            }
        }
    }

    @Command(name = "logs", description = "Show or follow worker logs")
    static class Logs implements Runnable {
        @Option(names = { "-f", "--follow" }, description = "Follow logs continuously")
        boolean follow;
        @Option(names = { "--worker-id" }, description = "Filter to a specific worker id")
        String wid;

        @Override
        public void run() {
            Database.init();
            java.nio.file.Path logsDir = java.nio.file.Paths.get(Database.baseDir(), "logs");
            if (!follow) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files
                        .newDirectoryStream(logsDir, "*.log")) {
                    for (java.nio.file.Path p : ds) {
                        String name = p.getFileName().toString();
                        String workerId = name.substring(0, name.length() - 4);
                        if (wid != null && !wid.equals(workerId))
                            continue;
                        System.out.println("=== " + workerId + " ===");
                        java.util.List<String> lines = java.nio.file.Files.readAllLines(p);
                        for (String line : lines)
                            System.out.println(line);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to read logs: " + e.getMessage());
                }
            } else {
                org.example.core.LogTailer tailer = new org.example.core.LogTailer(logsDir, wid);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> tailer.requestStop()));
                tailer.run();
            }
        }
    }
}