package com.enterprise.ratelimiter.exception;

public class UnknownCustomerException extends RateLimitException {
    public UnknownCustomerException(String message) {
        super(message);
    }
}
