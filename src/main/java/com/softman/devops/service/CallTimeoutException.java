package com.softman.devops.service;

public final class CallTimeoutException extends Exception {
    public CallTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
