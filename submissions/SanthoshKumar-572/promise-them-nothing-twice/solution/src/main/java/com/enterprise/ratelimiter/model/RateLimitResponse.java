package com.enterprise.ratelimiter.model;

public record RateLimitResponse(
        String customerName,
        long allocated,
        long used,
        String status
) {}
