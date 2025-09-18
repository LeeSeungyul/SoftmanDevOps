package com.softman.devops.service;

public final class UpstreamErrorException extends Exception {
    private final int statusCode;

    public UpstreamErrorException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public UpstreamErrorException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }
}
