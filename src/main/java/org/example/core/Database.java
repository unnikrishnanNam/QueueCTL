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
                    "last_error TEXT, " +
                    "output TEXT, " +
                    "priority INTEGER NOT NULL, " +
                    "locked_by TEXT, " +
                    "locked_at INTEGER" +
                    ")");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
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
            if (Files.exists(p)) Files.delete(p);
        } catch (Exception e) {
            // ignore
        }
    }
}
