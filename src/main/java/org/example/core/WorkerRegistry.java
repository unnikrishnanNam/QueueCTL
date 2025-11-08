package org.example.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WorkerRegistry {
    public void register(String workerId) {
        Database.init();
        String sql = "INSERT INTO workers(worker_id,status,last_heartbeat,started_at) VALUES(?,?,?,?)" +
                " ON CONFLICT(worker_id) DO UPDATE SET status=excluded.status, last_heartbeat=excluded.last_heartbeat, started_at=excluded.started_at";
        long now = Instant.now().getEpochSecond();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workerId);
            ps.setString(2, "IDLE");
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void heartbeat(String workerId, String status) {
        String sql = "UPDATE workers SET status=?, last_heartbeat=? WHERE worker_id=?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, Instant.now().getEpochSecond());
            ps.setString(3, workerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markStopped(String workerId) {
        String sql = "UPDATE workers SET status='STOPPED', last_heartbeat=? WHERE worker_id=?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setString(2, workerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Counts counts() {
        String sql = "SELECT status, COUNT(1) c FROM workers WHERE status IN ('IDLE','BUSY') GROUP BY status";
        int idle = 0, busy = 0;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String s = rs.getString("status");
                    int cnt = rs.getInt("c");
                    if ("IDLE".equals(s))
                        idle = cnt;
                    else if ("BUSY".equals(s))
                        busy = cnt;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new Counts(idle, busy);
    }

    public List<WorkerInfo> list() {
        String sql = "SELECT worker_id, status, last_heartbeat, started_at FROM workers ORDER BY started_at";
        List<WorkerInfo> out = new ArrayList<>();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WorkerInfo(
                            rs.getString("worker_id"),
                            rs.getString("status"),
                            rs.getLong("last_heartbeat"),
                            rs.getLong("started_at")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public record Counts(int idle, int busy) {
    }

    public record WorkerInfo(String workerId, String status, long lastHeartbeat, long startedAt) {
    }
}
