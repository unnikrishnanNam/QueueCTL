package org.example.cli;

import org.example.core.Database;
import org.example.core.WebServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Command(name = "webserver", description = "Real-time web dashboard server.", subcommands = {
        WebServerCommand.Start.class, WebServerCommand.Stop.class, WebServerCommand.Status.class
})
public class WebServerCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("webserver [start|stop|status]");
    }

    static Path pidFile() {
        return Paths.get(Database.baseDir(), "webserver.pid");
    }

    @Command(name = "start", description = "Start the web dashboard server")
    public static class Start implements Runnable {
        @Option(names = "--port", description = "Port to listen on", defaultValue = "8080")
        int port;

        @Option(names = "--foreground", description = "Run in foreground (do not daemonize)")
        boolean foreground;

        @Override
        public void run() {
            Database.init();
            if (foreground) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> WebServer.stop()));
                WebServer.start(port);
                // block forever
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException ignored) {
                }
            } else {
                try {
                    if (Files.exists(pidFile())) {
                        System.out.println(
                                "Webserver appears to be running (pid file exists). Use 'webserver status' or 'webserver stop'.");
                        return;
                    }
                    String jar = new java.io.File(
                            org.example.Main.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .getPath();
                    String userHome = System.getProperty("user.home");
                    String logsOut = Paths.get(Database.baseDir(), "webserver.nohup.out").toString();
                    String cmd = String.format(
                            "nohup java -Duser.home='%s' -jar '%s' webserver start --port %d --foreground > %s 2>&1 & echo $!",
                            userHome, jar, port, logsOut);
                    ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
                    Process p = pb.start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String pid = r.readLine();
                        if (pid != null) {
                            Files.writeString(pidFile(), pid);
                            System.out.println("Started webserver: http://localhost:" + port + " (pid=" + pid + ")");
                        } else {
                            System.err.println("Failed to read webserver pid");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to start webserver: " + e.getMessage());
                }
            }
        }
    }

    @Command(name = "stop", description = "Stop the web dashboard server")
    public static class Stop implements Runnable {
        @Override
        public void run() {
            try {
                if (!Files.exists(pidFile())) {
                    System.out.println("No pid file found. Webserver not running?");
                    return;
                }
                String pid = Files.readString(pidFile()).trim();
                new ProcessBuilder("/bin/kill", "-TERM", pid).start();
                Files.deleteIfExists(pidFile());
                System.out.println("Sent TERM to webserver pid=" + pid);
            } catch (Exception e) {
                System.err.println("Failed to stop webserver: " + e.getMessage());
            }
        }
    }

    @Command(name = "status", description = "Check webserver status")
    public static class Status implements Runnable {
        @Override
        public void run() {
            try {
                if (!Files.exists(pidFile())) {
                    System.out.println("Webserver: not running (no pid file)");
                    return;
                }
                String pid = Files.readString(pidFile()).trim();
                Process p = new ProcessBuilder("/bin/ps", "-p", pid, "-o", "pid,command").start();
                p.waitFor();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    r.readLine();
                    String line = r.readLine();
                    if (line == null || line.isBlank()) {
                        System.out.println("Webserver: stale pid (process not found).");
                    } else {
                        System.out.println("Webserver running: " + line);
                        System.out.println("Open http://localhost:8080 in your browser (or the configured port).");
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to get webserver status: " + e.getMessage());
            }
        }
    }
}
