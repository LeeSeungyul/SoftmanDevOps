package com.softman.devops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServiceConfigurationTest {

    @Test
    void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () ->
                new ServiceConfiguration(0, 5, Duration.ofSeconds(1), Duration.ofSeconds(2), LogLevel.INFO, Path.of(".")));
    }

    @Test
    void rejectsNonPositiveMaxConnections() {
        assertThrows(IllegalArgumentException.class, () ->
                new ServiceConfiguration(8080, 0, Duration.ofSeconds(1), Duration.ofSeconds(2), LogLevel.INFO, Path.of(".")));
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class, () ->
                new ServiceConfiguration(8080, 5, Duration.ZERO, Duration.ofSeconds(2), LogLevel.INFO, Path.of(".")));
        assertThrows(IllegalArgumentException.class, () ->
                new ServiceConfiguration(8080, 5, Duration.ofSeconds(1), Duration.ofSeconds(0), LogLevel.INFO, Path.of(".")));
    }

    @Test
    void storesValues() {
        ServiceConfiguration configuration = new ServiceConfiguration(9000, 10, Duration.ofSeconds(3), Duration.ofSeconds(5), LogLevel.DEBUG, Path.of("logs"));
        assertEquals(9000, configuration.getPort());
        assertEquals(10, configuration.getMaxConnections());
        assertEquals(Duration.ofSeconds(3), configuration.getRequestTimeout());
        assertEquals(Duration.ofSeconds(5), configuration.getJobTimeout());
        assertEquals(LogLevel.DEBUG, configuration.getLogLevel());
        assertEquals(Path.of("logs"), configuration.getLogDirectory());
    }
}
