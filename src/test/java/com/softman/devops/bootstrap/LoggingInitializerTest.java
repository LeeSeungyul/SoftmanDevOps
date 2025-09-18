package com.softman.devops.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.softman.devops.config.LogLevel;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LoggingInitializerTest {
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("LOG_DIR");
        System.clearProperty("LOG_LEVEL");
        System.clearProperty("LOG_DATE");
    }

    @Test
    void initializeLoggingSetsSystemProperties() {
        Path logDir = Path.of("/tmp/test-logs");
        LogLevel logLevel = LogLevel.DEBUG;

        LoggingInitializer.initializeLogging(logDir, logLevel);

        assertEquals("/tmp/test-logs", System.getProperty("LOG_DIR"));
        assertEquals("DEBUG", System.getProperty("LOG_LEVEL"));
        assertEquals(LocalDate.now().format(LOG_DATE_FORMAT), System.getProperty("LOG_DATE"));
    }

    @Test
    void initializeLoggingWithInfoLevel() {
        Path logDir = Path.of("./logs");
        LogLevel logLevel = LogLevel.INFO;

        LoggingInitializer.initializeLogging(logDir, logLevel);

        assertTrue(System.getProperty("LOG_DIR").endsWith("logs"));
        assertEquals("INFO", System.getProperty("LOG_LEVEL"));
        assertNotNull(System.getProperty("LOG_DATE"));
    }

    @Test
    void initializeLoggingWithErrorLevel() {
        Path logDir = Path.of(".");
        LogLevel logLevel = LogLevel.ERROR;

        LoggingInitializer.initializeLogging(logDir, logLevel);

        assertNotNull(System.getProperty("LOG_DIR"));
        assertEquals("ERROR", System.getProperty("LOG_LEVEL"));
        assertEquals(LocalDate.now().format(LOG_DATE_FORMAT), System.getProperty("LOG_DATE"));
    }
}