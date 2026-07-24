package com.enterprise.ratelimiter.service;

import com.enterprise.ratelimiter.config.RateLimitProperties;
import com.enterprise.ratelimiter.config.RateLimitProperties.CustomerConfig;
import com.enterprise.ratelimiter.exception.UnknownCustomerException;
import com.enterprise.ratelimiter.model.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

@Service
public class RedisTokenBucketRateLimiter implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private final RedisOperations<String, String> stringRedisTemplate;
    private final RedisScript<List> rateLimiterScript;
    private final RateLimitProperties rateLimitProperties;
    private Supplier<LocalTime> timeProvider = LocalTime::now;
    private Supplier<Long> epochTimeProvider = System::currentTimeMillis;

    public RedisTokenBucketRateLimiter(
            RedisOperations<String, String> stringRedisTemplate,
            RedisScript<List> rateLimiterScript,
            RateLimitProperties rateLimitProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimiterScript = rateLimiterScript;
        this.rateLimitProperties = rateLimitProperties;
    }

    // Setter to allow mock time provider in testing
    public void setTimeProvider(Supplier<LocalTime> timeProvider) {
        this.timeProvider = timeProvider;
    }

    // Setter to allow mock epoch time provider in testing
    public void setEpochTimeProvider(Supplier<Long> epochTimeProvider) {
        this.epochTimeProvider = epochTimeProvider;
    }

    @Override
    public RateLimitResult checkRateLimit(String customerId) {
        CustomerConfig customerConfig = rateLimitProperties.getCustomers().get(customerId);
        if (customerConfig == null) {
            log.warn("Unauthorized rate limit check: Customer ID '{}' is not registered in configuration", customerId);
            throw new UnknownCustomerException("Unknown customer ID: " + customerId);
        }

        LocalTime currentLocalTime = timeProvider.get();
        int limit = resolveLimit(customerConfig, currentLocalTime);
        String policyName = resolvePolicyName(customerConfig, currentLocalTime);
        double refillRate = (double) limit / 60000.0; // tokens per millisecond
        long now = epochTimeProvider.get();

        String redisKey = "rate_limit:customers:" + customerId;

        // Execute Lua script atomically
        List<Long> result;
        try {
            @SuppressWarnings("unchecked")
            List<Long> scriptResult = stringRedisTemplate.execute(
                    rateLimiterScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(limit),
                    String.valueOf(refillRate),
                    String.valueOf(now),
                    "1"
            );
            result = scriptResult;
        } catch (Exception e) {
            log.error("Redis connection or execution error for customer '{}'. Failing open to maintain service availability.", customerId, e);
            // Fail open: allowed=true, remainingTokens=-1, limit=limit, policyName="fallback-fail-open", waitTimeMs=0
            return new RateLimitResult(true, -1, limit, "fallback-fail-open", 0L);
        }

        if (result == null || result.size() < 3) {
            log.error("Failed to execute rate limit Lua script for customer '{}'. Invalid script output: {}", customerId, result);
            return new RateLimitResult(false, 0, limit, policyName, 0L);
        }

        boolean allowed = result.get(0) == 1L;
        long remainingTokens = result.get(1);
        long elapsedMs = result.get(2);
        long waitTimeMs = result.size() >= 4 ? result.get(3) : 0L;

        // Logging events
        if (elapsedMs == -1) {
            log.info("Refill Event: Initialized new rate limit bucket for customer '{}' with capacity {} tokens (policy: '{}')", 
                    customerId, limit, policyName);
        } else if (elapsedMs > 0) {
            double refilledAmount = elapsedMs * refillRate;
            log.info("Refill Event: Refilled bucket for customer '{}' with {} tokens (elapsed time: {} ms, policy: '{}')", 
                    customerId, String.format("%.4f", refilledAmount), elapsedMs, policyName);
        }

        if (allowed) {
            log.info("Allowed Request: Customer '{}' has {} remaining tokens under policy '{}'", customerId, remainingTokens, policyName);
        } else {
            log.warn("Rejected Request: Customer '{}' has 0 remaining tokens and exceeded rate limit of {} RPM under policy '{}' (retry after {} ms)", 
                    customerId, limit, policyName, waitTimeMs);
        }

        return new RateLimitResult(allowed, remainingTokens, limit, policyName, waitTimeMs);
    }

    // Public for testing and evaluation
    public int resolveLimit(CustomerConfig customerConfig, LocalTime currentTime) {
        if (customerConfig.getSchedules() == null || customerConfig.getSchedules().isEmpty()) {
            return customerConfig.getLimit();
        }

        for (RateLimitProperties.ScheduledLimit schedule : customerConfig.getSchedules()) {
            try {
                LocalTime start = LocalTime.parse(schedule.getStartTime());
                LocalTime end = LocalTime.parse(schedule.getEndTime());

                boolean matches;
                if (start.isBefore(end)) {
                    // Same day (e.g. 02:00 to 05:00)
                    matches = !currentTime.isBefore(start) && !currentTime.isAfter(end);
                } else {
                    // Crosses midnight (e.g. 22:00 to 04:00)
                    matches = !currentTime.isBefore(start) || !currentTime.isAfter(end);
                }

                if (matches) {
                    return schedule.getLimit();
                }
            } catch (Exception e) {
                log.error("Failed to parse schedule times for: start={}, end={}", 
                        schedule.getStartTime(), schedule.getEndTime(), e);
            }
        }

        return customerConfig.getLimit();
    }

    // Public for testing and evaluation
    public String resolvePolicyName(CustomerConfig customerConfig, LocalTime currentTime) {
        if (customerConfig.getSchedules() == null || customerConfig.getSchedules().isEmpty()) {
            return "default";
        }

        for (RateLimitProperties.ScheduledLimit schedule : customerConfig.getSchedules()) {
            try {
                LocalTime start = LocalTime.parse(schedule.getStartTime());
                LocalTime end = LocalTime.parse(schedule.getEndTime());

                boolean matches;
                if (start.isBefore(end)) {
                    matches = !currentTime.isBefore(start) && !currentTime.isAfter(end);
                } else {
                    matches = !currentTime.isBefore(start) || !currentTime.isAfter(end);
                }

                if (matches) {
                    return schedule.getName();
                }
            } catch (Exception e) {
                // Ignore, logged in resolveLimit
            }
        }

        return "default";
    }
}
