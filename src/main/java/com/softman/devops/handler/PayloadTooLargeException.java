package com.softman.devops.handler;

public final class PayloadTooLargeException extends Exception {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
