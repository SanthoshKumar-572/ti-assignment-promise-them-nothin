package com.enterprise.ratelimiter.service;

import com.enterprise.ratelimiter.model.RateLimitResult;

public interface RateLimiterService {
    /**
     * Checks if the request is allowed for the given customer based on their rate limit configurations.
     *
     * @param customerId the customer identifier
     * @return the RateLimitResult containing details of the evaluation
     */
    RateLimitResult checkRateLimit(String customerId);
}
