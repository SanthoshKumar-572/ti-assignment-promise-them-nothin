package com.enterprise.ratelimiter.exception;

public class RateExceededException extends RateLimitException {
    public RateExceededException(String message) {
        super(message);
    }
}
