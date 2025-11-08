package org.example.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;

public class LogTailer implements Runnable {
    private final Path logsDir;
    private final String filterWorkerId; // null = all
    private final AtomicBooleanStop stop = new AtomicBooleanStop();

    public LogTailer(Path logsDir, String filterWorkerId) {
        this.logsDir = logsDir;
        this.filterWorkerId = filterWorkerId;
    }

    public void requestStop() {
        stop.request();
    }

    @Override
    public void run() {
        Map<Path, Long> positions = new HashMap<>();
        while (!stop.isRequested() && !Database.isStopRequested()) {
            try {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(logsDir, "*.log")) {
                    for (Path p : ds) {
                        String name = p.getFileName().toString();
                        String wid = name.substring(0, name.length() - 4);
                        if (filterWorkerId != null && !filterWorkerId.equals(wid))
                            continue;
                        long pos = positions.getOrDefault(p, 0L);
                        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
                            raf.seek(pos);
                            String line;
                            while ((line = raf.readLine()) != null) {
                                System.out.println(line);
                            }
                            positions.put(p, raf.getFilePointer());
                        } catch (IOException ignored) {
                        }
                    }
                }
                Thread.sleep(500);
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    static class AtomicBooleanStop {
        private volatile boolean req = false;

        void request() {
            req = true;
        }

        boolean isRequested() {
            return req;
        }
    }
}
