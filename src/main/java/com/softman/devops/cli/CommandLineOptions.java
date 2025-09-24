package com.softman.devops.cli;

import java.util.Optional;

public record CommandLineOptions(boolean helpRequested,
                                 Optional<Integer> port,
                                 int maxConnections,
                                 int timeoutSeconds,
                                 int jobTimeoutSeconds,
                                 int logLevelCode,
                                 Optional<String> logDirectory) {
}
