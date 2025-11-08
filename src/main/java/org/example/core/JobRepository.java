package org.example.core;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class JobRepository {
    private final int backoffBase;

    public JobRepository(int backoffBase) {
        this.backoffBase = Math.max(2, backoffBase);
        Database.init();
    }

    public void createJob(Job job) {
        String sql = "INSERT INTO jobs (id, command, state, attempts, max_retries, created_at, updated_at, available_at, priority, run_at, timeout_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long now = Instant.now().getEpochSecond();
            ps.setString(1, job.getId());
            ps.setString(2, job.getCommand());
            ps.setString(3, job.getState().name());
            ps.setInt(4, job.getAttempts());
            ps.setInt(5, job.getMaxRetries());
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setLong(8, job.getAvailableAtEpoch());
            ps.setInt(9, job.getPriority());
            if (job.getRunAtEpoch() == null) {
                ps.setNull(10, Types.INTEGER);
            } else {
                ps.setLong(10, job.getRunAtEpoch());
            }
            ps.setInt(11, job.getTimeoutSeconds());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert job", e);
        }
    }

    /**
     * Atomically claim a pending job and return it, or null if none available.
     */
    public Job claimPendingJob(String workerId) {
        String update = "UPDATE jobs SET state='PROCESSING', locked_by=?, locked_at=?, updated_at=? WHERE id = (SELECT id FROM jobs WHERE state='PENDING' AND available_at <= ? ORDER BY priority DESC, available_at ASC, created_at ASC LIMIT 1)";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(update)) {
            long now = Instant.now().getEpochSecond();
            ps.setString(1, workerId);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setLong(4, now);
            int affected = ps.executeUpdate();
            if (affected == 0)
                return null;
            // select the row with this worker/locked_at
            String sel = "SELECT id,command,state,attempts,max_retries,created_at,updated_at,available_at,last_error,output,priority,run_at,timeout_seconds FROM jobs WHERE locked_by = ? AND locked_at = ? LIMIT 1";
            try (PreparedStatement ps2 = c.prepareStatement(sel)) {
                ps2.setString(1, workerId);
                ps2.setLong(2, now);
                try (ResultSet rs = ps2.executeQuery()) {
                    if (rs.next())
                        return rowToJob(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim job", e);
        }
        return null;
    }

    public void markJobCompleted(String jobId, String output) {
        String sql = "UPDATE jobs SET state='COMPLETED', updated_at=?, output=?, locked_by=NULL, locked_at=NULL WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setString(2, output);
            ps.setString(3, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void handleFailedAttempt(Job job, String error) {
        int attempts = job.getAttempts() + 1;
        long now = Instant.now().getEpochSecond();
        if (attempts > job.getMaxRetries()) {
            // move to dead
            String sql = "UPDATE jobs SET state='DEAD', attempts=?, last_error=?, updated_at=?, locked_by=NULL, locked_at=NULL WHERE id=?";
            try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, attempts);
                ps.setString(2, error);
                ps.setLong(3, now);
                ps.setString(4, job.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        } else {
            // schedule retry with exponential backoff
            long delay = (long) Math.pow(backoffBase, attempts);
            long avail = now + delay;
            String sql = "UPDATE jobs SET state='PENDING', attempts=?, last_error=?, available_at=?, updated_at=?, locked_by=NULL, locked_at=NULL WHERE id=?";
            try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, attempts);
                ps.setString(2, error);
                ps.setLong(3, avail);
                ps.setLong(4, now);
                ps.setString(5, job.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public List<Job> listJobsByState(String stateFilter) {
        List<Job> out = new ArrayList<>();
        String sql;
        if (stateFilter == null)
            sql = "SELECT id,command,state,attempts,max_retries,created_at,updated_at,available_at,last_error,output,priority,run_at,timeout_seconds FROM jobs ORDER BY created_at DESC";
        else
            sql = "SELECT id,command,state,attempts,max_retries,created_at,updated_at,available_at,last_error,output,priority,run_at,timeout_seconds FROM jobs WHERE state = ? ORDER BY created_at DESC";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (stateFilter != null)
                ps.setString(1, stateFilter.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(rowToJob(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public Job getJobById(String id) {
        String sql = "SELECT id,command,state,attempts,max_retries,created_at,updated_at,available_at,last_error,output,priority,run_at,timeout_seconds FROM jobs WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rowToJob(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public void retryDeadJob(String id) {
        String sql = "UPDATE jobs SET state='PENDING', attempts=0, available_at=?, updated_at=?, last_error=NULL WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long now = Instant.now().getEpochSecond();
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Integer> stateCounts() {
        String sql = "SELECT state, COUNT(1) AS c FROM jobs GROUP BY state";
        Map<String, Integer> m = new HashMap<>();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString("state"), rs.getInt("c"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    public int activeWorkerCount() {
        String sql = "SELECT COUNT(DISTINCT locked_by) AS w FROM jobs WHERE state='PROCESSING' AND locked_by IS NOT NULL";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt("w");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    private Job rowToJob(ResultSet rs) throws SQLException {
        Job j = new Job();
        j.setId(rs.getString("id"));
        j.setCommand(rs.getString("command"));
        j.setState(JobState.valueOf(rs.getString("state")));
        j.setAttempts(rs.getInt("attempts"));
        j.setMaxRetries(rs.getInt("max_retries"));
        j.setCreatedAt(Instant.ofEpochSecond(rs.getLong("created_at")));
        j.setUpdatedAt(Instant.ofEpochSecond(rs.getLong("updated_at")));
        j.setAvailableAtEpoch(rs.getLong("available_at"));
        j.setLastError(rs.getString("last_error"));
        j.setOutput(rs.getString("output"));
        j.setPriority(rs.getInt("priority"));
        try {
            long runAt = rs.getLong("run_at");
            if (!rs.wasNull())
                j.setRunAtEpoch(runAt);
        } catch (SQLException ignored) {
        }
        try {
            int timeout = rs.getInt("timeout_seconds");
            if (!rs.wasNull())
                j.setTimeoutSeconds(timeout);
        } catch (SQLException ignored) {
        }
        return j;
    }
}
