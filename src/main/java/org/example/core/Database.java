package org.example.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private static final String DB_FILE = System.getProperty("user.home") + "/.queuectl/queuectl.db";
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_FILE;
    private static final String BASE_DIR = System.getProperty("user.home") + "/.queuectl";

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
    }

    public static Connection getConnection() throws SQLException {
        // set busy timeout via connection parameter (milliseconds)
        return DriverManager.getConnection(JDBC_URL + "?busy_timeout=5000");
    }

    public static void init() {
        try {
            Path p = Paths.get(System.getProperty("user.home"), ".queuectl");
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DB directory", e);
        }

        try {
            Path logs = Paths.get(BASE_DIR, "logs");
            if (!Files.exists(logs))
                Files.createDirectories(logs);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create logs directory", e);
        }

        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(true);

            s.executeUpdate("CREATE TABLE IF NOT EXISTS jobs (" +
                    "id TEXT PRIMARY KEY, " +
                    "command TEXT NOT NULL, " +
                    "state TEXT NOT NULL, " +
                    "attempts INTEGER NOT NULL, " +
                    "max_retries INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "available_at INTEGER NOT NULL, " +
                    "run_at INTEGER, " +
                    "timeout_seconds INTEGER NOT NULL DEFAULT 0, " +
                    "last_error TEXT, " +
                    "output TEXT, " +
                    "priority INTEGER NOT NULL, " +
                    "locked_by TEXT, " +
                    "locked_at INTEGER" +
                    ")");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS workers (" +
                    "worker_id TEXT PRIMARY KEY, " +
                    "status TEXT NOT NULL, " +
                    "last_heartbeat INTEGER NOT NULL, " +
                    "started_at INTEGER NOT NULL" +
                    ")");

            // performance samples (kept lightweight; prune per worker)
            s.executeUpdate("CREATE TABLE IF NOT EXISTS worker_perf (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "worker_id TEXT NOT NULL, " +
                    "ts_ms INTEGER NOT NULL, " +
                    "heap_used_bytes INTEGER NOT NULL, " +
                    "cpu_load REAL NOT NULL, " +
                    "last_job_duration_ms INTEGER" +
                    ")");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_worker_perf_worker_ts ON worker_perf(worker_id, ts_ms)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }

        // Migrations: add columns if missing
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            // run_at column
            try {
                s.executeUpdate("ALTER TABLE jobs ADD COLUMN run_at INTEGER");
            } catch (SQLException ignore) {
            }
            // timeout_seconds column
            try {
                s.executeUpdate("ALTER TABLE jobs ADD COLUMN timeout_seconds INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignore) {
            }
            // workers table: add current job & perf state columns if missing
            try {
                s.executeUpdate("ALTER TABLE workers ADD COLUMN current_job_id TEXT");
            } catch (SQLException ignore) {
            }
            try {
                s.executeUpdate("ALTER TABLE workers ADD COLUMN current_job_start_ms INTEGER");
            } catch (SQLException ignore) {
            }
            try {
                s.executeUpdate("ALTER TABLE workers ADD COLUMN last_finished_ms INTEGER");
            } catch (SQLException ignore) {
            }
        } catch (SQLException e) {
            // ignore migration failures; columns may already exist
        }
    }

    public static String baseDir() {
        return BASE_DIR;
    }

    public static boolean isStopRequested() {
        try {
            return Files.exists(Paths.get(BASE_DIR, "stop.flag"));
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestStop() {
        try {
            Path p = Paths.get(BASE_DIR, "stop.flag");
            if (!Files.exists(p)) {
                Files.createFile(p);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to request stop", e);
        }
    }

    public static void clearStop() {
        try {
            Path p = Paths.get(BASE_DIR, "stop.flag");
            if (Files.exists(p))
                Files.delete(p);
        } catch (Exception e) {
            // ignore
        }
    }
}
