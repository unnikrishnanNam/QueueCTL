package org.example.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DaemonManager {
    private static Path pidFile() {
        return Paths.get(Database.baseDir(), "worker.pid");
    }

    public static void startDaemon(int count) {
        try {
            if (Files.exists(pidFile())) {
                System.out.println(
                        "Daemon appears to be running (pid file exists). Use 'worker daemon status' or 'worker daemon stop'.");
                return;
            }
            String jar = new java.io.File(
                    org.example.Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
            String logsOut = Paths.get(Database.baseDir(), "worker.nohup.out").toString();
            String cmd = String.format("nohup java -jar '%s' worker start --count %d > %s 2>&1 & echo $!", jar, count,
                    logsOut);
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String pid = r.readLine();
                if (pid != null) {
                    Files.writeString(pidFile(), pid);
                    System.out.println("Started daemon, pid=" + pid);
                } else {
                    System.err.println("Failed to read daemon pid");
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to start daemon: " + e.getMessage());
        }
    }

    public static void stopDaemon() {
        try {
            if (!Files.exists(pidFile())) {
                System.out.println("No pid file found. Daemon not running?");
                return;
            }
            String pid = Files.readString(pidFile()).trim();
            // request graceful stop for workers
            Database.requestStop();
            // send SIGTERM to the launcher JVM
            new ProcessBuilder("/bin/kill", "-TERM", pid).start();
            System.out.println("Sent TERM to pid=" + pid + ", requested graceful worker stop.");
            Files.deleteIfExists(pidFile());
        } catch (IOException e) {
            System.err.println("Failed to stop daemon: " + e.getMessage());
        }
    }

    public static void statusDaemon() {
        try {
            if (!Files.exists(pidFile())) {
                System.out.println("Daemon: not running (no pid file)");
                return;
            }
            String pid = Files.readString(pidFile()).trim();
            Process p = new ProcessBuilder("/bin/ps", "-p", pid, "-o", "pid,command").start();
            p.waitFor();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                r.readLine(); // skip header
                String line = r.readLine();
                if (line == null || line.isBlank()) {
                    System.out.println(
                            "Daemon: stale pid (process not found). Remove pid file or run 'worker daemon start'.");
                } else {
                    System.out.println("Daemon running: " + line);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get daemon status: " + e.getMessage());
        }
    }

    public static void installUnitTemplates() {
        try {
            String jarPath = new java.io.File(
                    org.example.Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
            String systemd = "[Unit]\nDescription=QueueCTL Workers\nAfter=network.target\n\n[Service]\nType=simple\nExecStart=/usr/bin/java -jar "
                    + jarPath
                    + " worker start --count 2\nRestart=on-failure\n\n[Install]\nWantedBy=multi-user.target\n";
            Path systemdPath = Paths.get(Database.baseDir(), "queuectl-worker.service");
            Files.writeString(systemdPath, systemd);

            String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    +
                    "<plist version=\"1.0\">\n<dict>\n" +
                    "  <key>Label</key><string>io.queuectl.worker</string>\n" +
                    "  <key>ProgramArguments</key>\n  <array>\n    <string>/usr/bin/java</string>\n    <string>-jar</string>\n    <string>"
                    + jarPath
                    + "</string>\n    <string>worker</string>\n    <string>start</string>\n    <string>--count</string>\n    <string>2</string>\n  </array>\n"
                    +
                    "  <key>RunAtLoad</key><true/>\n" +
                    "</dict>\n</plist>\n";
            Path plistPath = Paths.get(Database.baseDir(), "io.queuectl.worker.plist");
            Files.writeString(plistPath, plist);

            System.out.println("Generated unit templates:\n- systemd: " + systemdPath + "\n- launchd: " + plistPath
                    + "\nUse your OS service manager to install/enable them.");
        } catch (Exception e) {
            System.err.println("Failed to write unit templates: " + e.getMessage());
        }
    }
}
