package com.enterprise.ratelimiter;

import com.enterprise.ratelimiter.config.RateLimitProperties;
import com.enterprise.ratelimiter.config.RateLimitProperties.CustomerConfig;
import com.enterprise.ratelimiter.exception.UnknownCustomerException;
import com.enterprise.ratelimiter.filter.RateLimitFilter;
import com.enterprise.ratelimiter.model.RateLimitResult;
import com.enterprise.ratelimiter.service.RateLimiterService;
import com.enterprise.ratelimiter.service.RedisTokenBucketRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class RateLimiterUnitTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimiterService mockRateLimiterService;

    private RedisOperations<String, String> mockRedisTemplate;
    private RedisScript<List> mockScript;
    private RateLimitProperties rateLimitProperties;
    private RedisTokenBucketRateLimiter rateLimiterService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockRedisTemplate = (RedisOperations<String, String>) Mockito.mock(RedisOperations.class);
        mockScript = (RedisScript<List>) Mockito.mock(RedisScript.class);
        rateLimitProperties = new RateLimitProperties();

        Map<String, CustomerConfig> customers = new HashMap<>();
        CustomerConfig starter = new CustomerConfig();
        starter.setLimit(60);
        customers.put("starter-company", starter);

        CustomerConfig growth = new CustomerConfig();
        growth.setLimit(300);
        customers.put("northwind", growth);

        rateLimitProperties.setCustomers(customers);

        rateLimiterService = new RedisTokenBucketRateLimiter(
                mockRedisTemplate,
                mockScript,
                rateLimitProperties
        );
    }

    // 1. Missing Customer Header -> 400 Bad Request
    @Test
    void testMissingCustomerHeader() throws Exception {
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.customerName").value("Missing"))
                .andExpect(jsonPath("$.allocated").value(0))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.status").value("❌"));
    }

    // 2. Unknown Customer -> 401 Unauthorized
    @Test
    void testUnknownCustomer() throws Exception {
        when(mockRateLimiterService.checkRateLimit("unknown-company"))
                .thenThrow(new UnknownCustomerException("Unknown customer ID: unknown-company"));

        mockMvc.perform(get("/api/test").header("X-Customer-Id", "unknown-company"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.customerName").value("unknown-company"))
                .andExpect(jsonPath("$.allocated").value(0))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.status").value("❌"));
    }

    // 3. Limit Exceeded -> 429 Too Many Requests
    @Test
    void testLimitExceeded() throws Exception {
        when(mockRateLimiterService.checkRateLimit("starter-company"))
                .thenReturn(new RateLimitResult(false, 0, 60, "default"));

        mockMvc.perform(get("/api/test").header("X-Customer-Id", "starter-company"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.customerName").value("starter-company"))
                .andExpect(jsonPath("$.allocated").value(60))
                .andExpect(jsonPath("$.used").value(60))
                .andExpect(jsonPath("$.status").value("❌"));
    }

    @Test
    void testLimitExceededWithRetryAfterHeader() throws Exception {
        when(mockRateLimiterService.checkRateLimit("starter-company"))
                .thenReturn(new RateLimitResult(false, 0, 60, "default", 2500L)); // 2500ms wait time

        mockMvc.perform(get("/api/test").header("X-Customer-Id", "starter-company"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3")) // 2.5 seconds rounded up
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.customerName").value("starter-company"))
                .andExpect(jsonPath("$.allocated").value(60))
                .andExpect(jsonPath("$.used").value(60))
                .andExpect(jsonPath("$.status").value("❌"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testServiceRedisFailureFailsOpen() {
        when(mockRedisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis is down"));

        RateLimitResult result = rateLimiterService.checkRateLimit("starter-company");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(-1);
        assertThat(result.limit()).isEqualTo(60);
        assertThat(result.policyName()).isEqualTo("fallback-fail-open");
    }

    // 4. Request Succeeds -> 200 OK with Headers
    @Test
    void testRequestSucceeds() throws Exception {
        when(mockRateLimiterService.checkRateLimit("starter-company"))
                .thenReturn(new RateLimitResult(true, 59, 60, "default"));

        mockMvc.perform(get("/api/test").header("X-Customer-Id", "starter-company"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(header().string("X-RateLimit-Remaining", "59"))
                .andExpect(header().string("X-RateLimit-Policy", "default"))
                .andExpect(jsonPath("$.customerName").value("starter-company"))
                .andExpect(jsonPath("$.allocated").value(60))
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.status").value("✔"));
    }


    // 5. Bucket Creation & Refill Service Evaluation
    @Test
    @SuppressWarnings("unchecked")
    void testServiceBucketCreation() {
        when(mockRedisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(List.of(1L, 59L, -1L)); // 1=allowed, 59=remaining tokens, -1=new bucket

        RateLimitResult result = rateLimiterService.checkRateLimit("starter-company");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(59);
        assertThat(result.limit()).isEqualTo(60);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testServiceTokenRefill() {
        when(mockRedisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(List.of(1L, 30L, 5000L)); // 1=allowed, 30=remaining, 5000ms elapsed

        RateLimitResult result = rateLimiterService.checkRateLimit("starter-company");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(30);
        assertThat(result.limit()).isEqualTo(60);
    }

    @Test
    void testServiceUnknownCustomerThrows() {
        assertThrows(UnknownCustomerException.class, () ->
                rateLimiterService.checkRateLimit("unknown-company")
        );
    }

    @Test
    void testResolveLimit_NoSchedules_ReturnsDefaultLimit() {
        CustomerConfig config = new CustomerConfig();
        config.setLimit(100);
        
        int resolved = rateLimiterService.resolveLimit(config, LocalTime.of(12, 0));
        assertThat(resolved).isEqualTo(100);
    }

    @Test
    void testResolveLimit_WithActiveSchedule_OverridesLimit() {
        CustomerConfig config = new CustomerConfig();
        config.setLimit(100);

        RateLimitProperties.ScheduledLimit schedule = new RateLimitProperties.ScheduledLimit();
        schedule.setName("nightly");
        schedule.setStartTime("02:00");
        schedule.setEndTime("05:00");
        schedule.setLimit(500);
        config.getSchedules().add(schedule);

        // Outside schedule
        int resolvedOutside = rateLimiterService.resolveLimit(config, LocalTime.of(1, 59));
        assertThat(resolvedOutside).isEqualTo(100);

        // Inside schedule
        int resolvedInside = rateLimiterService.resolveLimit(config, LocalTime.of(3, 0));
        assertThat(resolvedInside).isEqualTo(500);

        // Border conditions
        int resolvedBorderStart = rateLimiterService.resolveLimit(config, LocalTime.of(2, 0));
        assertThat(resolvedBorderStart).isEqualTo(500);

        int resolvedBorderEnd = rateLimiterService.resolveLimit(config, LocalTime.of(5, 0));
        assertThat(resolvedBorderEnd).isEqualTo(500);
    }

    @Test
    void testResolveLimit_WithMidnightSchedule_OverridesLimit() {
        CustomerConfig config = new CustomerConfig();
        config.setLimit(100);

        RateLimitProperties.ScheduledLimit schedule = new RateLimitProperties.ScheduledLimit();
        schedule.setName("overnight");
        schedule.setStartTime("22:00");
        schedule.setEndTime("04:00");
        schedule.setLimit(800);
        config.getSchedules().add(schedule);

        // Inside schedule (before midnight)
        int resolvedBeforeMidnight = rateLimiterService.resolveLimit(config, LocalTime.of(23, 30));
        assertThat(resolvedBeforeMidnight).isEqualTo(800);

        // Inside schedule (after midnight)
        int resolvedAfterMidnight = rateLimiterService.resolveLimit(config, LocalTime.of(2, 0));
        assertThat(resolvedAfterMidnight).isEqualTo(800);

        // Outside schedule
        int resolvedOutside = rateLimiterService.resolveLimit(config, LocalTime.of(12, 0));
        assertThat(resolvedOutside).isEqualTo(100);
    }
}

