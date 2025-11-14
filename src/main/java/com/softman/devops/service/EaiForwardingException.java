package com.softman.devops.service;

public final class EaiForwardingException extends Exception {
    private final int statusCode;

    public EaiForwardingException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public EaiForwardingException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
