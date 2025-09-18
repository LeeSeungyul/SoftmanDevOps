package com.softman.devops.cli;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CommandLineParser {
    private static final int DEFAULT_MAX_CONNECTIONS = 5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_JOB_TIMEOUT_SECONDS = 180;
    private static final int DEFAULT_LOG_LEVEL = 2;

    public CommandLineOptions parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("Arguments must not be null");
        }

        boolean helpRequested = false;
        Map<String, String> values = new HashMap<>();

        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if ("--help".equals(token)) {
                helpRequested = true;
                continue;
            }
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Invalid option: " + token);
            }
            String key = token.substring(2);
            if (key.isBlank()) {
                throw new IllegalArgumentException("Invalid option: " + token);
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option --" + key);
            }
            String value = args[++index];
            if (value.startsWith("--")) {
                throw new IllegalArgumentException("Missing value for option --" + key);
            }
            if (values.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate option: --" + key);
            }
            values.put(key.toLowerCase(Locale.ROOT), value);
        }

        Optional<Integer> port = parseInteger(values.get("port"));
        int maxConnections = parseInteger(values.get("maxcon"), DEFAULT_MAX_CONNECTIONS, "maxcon");
        int timeoutSeconds = parseInteger(values.get("timeout"), DEFAULT_TIMEOUT_SECONDS, "timeout");
        int jobTimeoutSeconds = parseInteger(values.get("jobtimeout"), DEFAULT_JOB_TIMEOUT_SECONDS, "jobtimeout");
        int logLevel = parseInteger(values.get("loglevel"), DEFAULT_LOG_LEVEL, "loglevel");

        if (logLevel < 1 || logLevel > 3) {
            throw new IllegalArgumentException("loglevel must be 1, 2, or 3");
        }

        Optional<String> logDirectory = Optional.ofNullable(values.get("logdir"));

        return new CommandLineOptions(helpRequested, port, maxConnections, timeoutSeconds, jobTimeoutSeconds, logLevel, logDirectory);
    }

    private Optional<Integer> parseInteger(String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        return Optional.of(parsePositiveInt(rawValue, "option"));
    }

    private int parseInteger(String rawValue, int defaultValue, String optionKey) {
        if (rawValue == null) {
            return defaultValue;
        }
        return parsePositiveInt(rawValue, optionKey);
    }

    private int parsePositiveInt(String rawValue, String optionKey) {
        try {
            int value = Integer.parseInt(rawValue);
            if (value <= 0) {
                throw new IllegalArgumentException(optionKey + " must be a positive integer");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(optionKey + " must be a positive integer", ex);
        }
    }

    public String buildHelpMessage() {
        return String.join(System.lineSeparator(),
                "SoftmanDevOps service options:",
                "  --help                Show this help and exit.",
                "  --port <number>       Listening port (required unless --help).",
                "  --maxcon <number>     Maximum simultaneous requests (default 5).",
                "  --timeout <seconds>   Per-call timeout in seconds (default 60).",
                "  --jobtimeout <seconds> Max total job duration in seconds (default 180).",
                "  --loglevel <1|2|3>    1=ERROR, 2=INFO, 3=DEBUG (default 2).",
                "  --logdir <path>       Directory for log files (default current directory)."
        );
    }
}
