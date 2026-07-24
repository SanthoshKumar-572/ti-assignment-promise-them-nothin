package com.enterprise.ratelimiter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerStatusDTO(
        String customerId,
        String plan,
        int normalLimit,
        Integer specialLimit,
        int activeLimit,
        String specialWindow,
        long remainingTokens,
        String status,
        String policyType
) {}
