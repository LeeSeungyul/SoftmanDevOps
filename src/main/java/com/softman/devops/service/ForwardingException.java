package com.softman.devops.service;

public final class ForwardingException extends Exception {
    private final int statusCode;

    public ForwardingException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ForwardingException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
