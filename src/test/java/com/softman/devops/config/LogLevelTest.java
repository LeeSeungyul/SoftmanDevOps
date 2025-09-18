package com.softman.devops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LogLevelTest {

    @Test
    void mapsNumericCodes() {
        assertEquals(LogLevel.ERROR, LogLevel.fromCode(1));
        assertEquals(LogLevel.INFO, LogLevel.fromCode(2));
        assertEquals(LogLevel.DEBUG, LogLevel.fromCode(3));
    }

    @Test
    void rejectsInvalidCode() {
        assertThrows(IllegalArgumentException.class, () -> LogLevel.fromCode(0));
    }
}
