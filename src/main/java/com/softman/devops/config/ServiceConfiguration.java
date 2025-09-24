package com.softman.devops.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class ServiceConfiguration {
    private final int port;
    private final int maxConnections;
    private final Duration requestTimeout;
    private final Duration jobTimeout;
    private final LogLevel logLevel;
    private final Path logDirectory;

    public ServiceConfiguration(int port,
                                int maxConnections,
                                Duration requestTimeout,
                                Duration jobTimeout,
                                LogLevel logLevel,
                                Path logDirectory) {
        this.port = validatePort(port);
        this.maxConnections = validateMaxConnections(maxConnections);
        this.requestTimeout = validateDuration(requestTimeout, "requestTimeout");
        this.jobTimeout = validateDuration(jobTimeout, "jobTimeout");
        this.logLevel = Objects.requireNonNull(logLevel, "logLevel");
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory");
    }

    private int validatePort(int value) {
        if (value <= 0 || value > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return value;
    }

    private int validateMaxConnections(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        return value;
    }

    private Duration validateDuration(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    public int getPort() {
        return port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getJobTimeout() {
        return jobTimeout;
    }

    public LogLevel getLogLevel() {
        return logLevel;
    }

    public Path getLogDirectory() {
        return logDirectory;
    }
}
