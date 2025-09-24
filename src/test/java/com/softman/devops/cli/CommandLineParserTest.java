package com.softman.devops.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandLineParserTest {

    @Test
    void parsesAllOptions() {
        CommandLineParser parser = new CommandLineParser();
        String[] args = {
                "--port", "8080",
                "--maxcon", "10",
                "--timeout", "30",
                "--jobtimeout", "100",
                "--loglevel", "3",
                "--logdir", "/tmp/logs"
        };

        CommandLineOptions options = parser.parse(args);

        assertEquals(8080, options.port().orElseThrow());
        assertEquals(10, options.maxConnections());
        assertEquals(30, options.timeoutSeconds());
        assertEquals(100, options.jobTimeoutSeconds());
        assertEquals(3, options.logLevelCode());
        assertEquals("/tmp/logs", options.logDirectory().orElseThrow());
    }

    @Test
    void appliesDefaultsWhenOptionalValuesMissing() {
        CommandLineParser parser = new CommandLineParser();
        String[] args = {"--port", "5050"};

        CommandLineOptions options = parser.parse(args);

        assertEquals(5050, options.port().orElseThrow());
        assertEquals(5, options.maxConnections());
        assertEquals(60, options.timeoutSeconds());
        assertEquals(180, options.jobTimeoutSeconds());
        assertEquals(2, options.logLevelCode());
        assertFalse(options.logDirectory().isPresent());
    }

    @Test
    void helpFlagIsRecognised() {
        CommandLineParser parser = new CommandLineParser();
        CommandLineOptions options = parser.parse(new String[]{"--help"});
        assertTrue(options.helpRequested());
        assertTrue(options.port().isEmpty());
    }

    @Test
    void invalidLogLevelThrows() {
        CommandLineParser parser = new CommandLineParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new String[]{"--port", "8080", "--loglevel", "5"}));
    }

    @Test
    void duplicateOptionThrows() {
        CommandLineParser parser = new CommandLineParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new String[]{"--port", "1", "--port", "2"}));
    }
}
