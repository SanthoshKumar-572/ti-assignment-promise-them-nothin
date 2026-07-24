package com.enterprise.ratelimiter.model;

public record RateLimitResult(
        boolean allowed,
        long remainingTokens,
        long limit,
        String policyName,
        long waitTimeMs
) {
    public RateLimitResult(boolean allowed, long remainingTokens, long limit, String policyName) {
        this(allowed, remainingTokens, limit, policyName, 0L);
    }
}
