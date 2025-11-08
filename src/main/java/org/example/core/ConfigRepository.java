package org.example.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ConfigRepository {
    public void set(String key, String value) {
        Database.init();
        String sql = "INSERT INTO config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set config", e);
        }
    }

    public String get(String key, String defaultValue) {
        Database.init();
        String sql = "SELECT value FROM config WHERE key=?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get config", e);
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public java.util.Map<String, String> listAll() {
        Database.init();
        String sql = "SELECT key, value FROM config ORDER BY key";
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list config", e);
        }
        return m;
    }
}
