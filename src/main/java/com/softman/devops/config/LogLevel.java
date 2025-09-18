package com.softman.devops.config;

import ch.qos.logback.classic.Level;

/**
 * Log severity mapping supporting numeric CLI levels.
 */
public enum LogLevel {
    ERROR(Level.ERROR),
    INFO(Level.INFO),
    DEBUG(Level.DEBUG);

    private final Level logbackLevel;

    LogLevel(Level logbackLevel) {
        this.logbackLevel = logbackLevel;
    }

    public static LogLevel fromCode(int code) {
        return switch (code) {
            case 1 -> ERROR;
            case 2 -> INFO;
            case 3 -> DEBUG;
            default -> throw new IllegalArgumentException("Unsupported log level code: " + code);
        };
    }

    public Level getLogbackLevel() {
        return logbackLevel;
    }
}
