package com.softman.devops.service;

public final class JobDeadlineExceededException extends Exception {
    public JobDeadlineExceededException(String message) {
        super(message);
    }
}
