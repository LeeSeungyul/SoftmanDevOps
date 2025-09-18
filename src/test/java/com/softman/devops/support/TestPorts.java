package com.softman.devops.support;

import java.io.IOException;
import java.net.ServerSocket;

public final class TestPorts {
    private TestPorts() {
    }

    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to allocate free port", exception);
        }
    }
}
