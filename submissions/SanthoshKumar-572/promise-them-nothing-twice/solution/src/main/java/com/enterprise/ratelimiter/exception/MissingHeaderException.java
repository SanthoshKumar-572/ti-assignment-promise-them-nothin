package com.enterprise.ratelimiter.exception;

public class MissingHeaderException extends RateLimitException {
    public MissingHeaderException(String message) {
        super(message);
    }
}
