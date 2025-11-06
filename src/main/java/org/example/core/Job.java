package org.example.core;

import java.time.Instant;

public class Job {
    private String id;
    private String command;
    private JobState state; // PENDING, PROCESSING, COMPLETED, FAILED, DEAD
    private int attempts;
    private int maxRetries;
    private Instant createdAt;
    private Instant updatedAt;
    private long availableAtEpoch; // epoch seconds when job becomes available
    private int priority; // Higher value, more priority
    private String lastError;
    private String output;

    public Job() {
    }

    public Job(String id, String command, int maxRetries, int priority) {
        this.id = id;
        this.command = command;
        this.state = JobState.PENDING;
        this.attempts = 0;
        this.maxRetries = maxRetries;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.availableAtEpoch = Instant.now().getEpochSecond();
        this.priority = priority;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public JobState getState() {
        return state;
    }

    public void setState(JobState state) {
        this.state = state;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getAvailableAtEpoch() {
        return availableAtEpoch;
    }

    public void setAvailableAtEpoch(long availableAtEpoch) {
        this.availableAtEpoch = availableAtEpoch;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    @Override
    public String toString() {
        return "Job{" +
                "id='" + id + '\'' +
                ", command='" + command + '\'' +
                ", state=" + state +
                ", attempts=" + attempts +
                ", maxRetries=" + maxRetries +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", availableAtEpoch=" + availableAtEpoch +
                ", priority=" + priority +
                '}';
    }
}