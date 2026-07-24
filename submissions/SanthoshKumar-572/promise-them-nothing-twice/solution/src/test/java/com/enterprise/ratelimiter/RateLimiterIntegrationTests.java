package com.enterprise.ratelimiter;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.enterprise.ratelimiter.service.RateLimiterService;
import com.enterprise.ratelimiter.service.RedisTokenBucketRateLimiter;
import com.enterprise.ratelimiter.config.RateLimitProperties;
import com.enterprise.ratelimiter.config.RateLimitProperties.CustomerConfig;
import java.time.LocalTime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class RateLimiterIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterIntegrationTests.class);
    private static boolean redisAvailable = false;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    static void checkRedisAvailability() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            redisAvailable = true;
            log.info("Redis is available. Running integration tests.");
        } catch (IOException e) {
            redisAvailable = false;
            log.warn("Redis is not running on localhost:6379. Skipping integration tests.");
        }
    }

    @BeforeEach
    void setUp() {
        // Skip tests if Redis is not running
        Assumptions.assumeTrue(redisAvailable, "Skipping: Redis server is not reachable on localhost:6379");
        if (redisTemplate != null) {
            redisTemplate.delete("rate_limit:customers:starter-company");
            redisTemplate.delete("rate_limit:customers:northwind");
        }

        // Use a fixed epoch timestamp by default to avoid time refill drift during tests
        if (rateLimiterService instanceof RedisTokenBucketRateLimiter) {
            long fixedTime = System.currentTimeMillis();
            ((RedisTokenBucketRateLimiter) rateLimiterService).setEpochTimeProvider(() -> fixedTime);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Restore default suppliers after each test
        if (rateLimiterService instanceof RedisTokenBucketRateLimiter) {
            ((RedisTokenBucketRateLimiter) rateLimiterService).setTimeProvider(LocalTime::now);
            ((RedisTokenBucketRateLimiter) rateLimiterService).setEpochTimeProvider(System::currentTimeMillis);
        }
    }


    @Test
    void testStarterCompanyLimit_60Succeed_61stFails() throws Exception {
        String customerId = "starter-company";

        // Send 60 requests: they should all succeed (200 OK)
        for (int i = 1; i <= 60; i++) {
            mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                    .andExpect(status().isOk());
        }

        // The 61st request should be blocked (429 Too Many Requests)
        mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void testNorthwindLimit_300Succeed_301stFails() throws Exception {
        String customerId = "northwind";

        // Set time to 10:00 AM (outside the nightly-batch schedule of 02:00 to 05:00)
        if (rateLimiterService instanceof RedisTokenBucketRateLimiter) {
            ((RedisTokenBucketRateLimiter) rateLimiterService).setTimeProvider(() -> LocalTime.of(10, 0));
        }

        try {
            // Send 300 requests: they should all succeed (200 OK)
            for (int i = 1; i <= 300; i++) {
                mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                        .andExpect(status().isOk());
            }

            // The 301st request should be blocked (429 Too Many Requests)
            mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                    .andExpect(status().isTooManyRequests());
        } finally {
            // Restore default time provider
            if (rateLimiterService instanceof RedisTokenBucketRateLimiter) {
                ((RedisTokenBucketRateLimiter) rateLimiterService).setTimeProvider(LocalTime::now);
            }
        }
    }

    @Test
    void testConcurrentRequests_ThreadSafety() throws Exception {
        String customerId = "starter-company";
        int totalRequests = 70; // Starter company has a limit of 60
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            tasks.add(() -> {
                try {
                    int status = mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    return status;
                } catch (Exception e) {
                    return 500;
                }
            });
        }

        List<Future<Integer>> futures = executorService.invokeAll(tasks);
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        int successfulRequests = 0;
        int rateLimitedRequests = 0;
        int otherErrors = 0;

        for (Future<Integer> future : futures) {
            int status = future.get();
            if (status == 200) {
                successfulRequests++;
            } else if (status == 429) {
                rateLimitedRequests++;
            } else {
                otherErrors++;
            }
        }

        log.info("Concurrent Test Results - Allowed: {}, Rejected: {}, Errors: {}", 
                successfulRequests, rateLimitedRequests, otherErrors);

        // Under high concurrency, exactly 60 requests should succeed, and remaining (10) should be rate-limited
        assertThat(successfulRequests).isEqualTo(60);
        assertThat(rateLimitedRequests).isEqualTo(10);
        assertThat(otherErrors).isZero();
    }

    @Test
    void testNorthwindNightlySchedule_1200Succeed_1201stFails() throws Exception {
        String customerId = "northwind";

        // Resolve schedule config dynamically from properties
        CustomerConfig customerConfig = rateLimitProperties.getCustomers().get(customerId);
        Assumptions.assumeTrue(customerConfig != null && !customerConfig.getSchedules().isEmpty());

        RateLimitProperties.ScheduledLimit schedule = customerConfig.getSchedules().get(0);
        LocalTime activeTime = LocalTime.parse(schedule.getStartTime()).plusMinutes(10); // 10 minutes inside window
        int configuredLimit = schedule.getLimit();

        // Set time to active window
        if (rateLimiterService instanceof RedisTokenBucketRateLimiter) {
            ((RedisTokenBucketRateLimiter) rateLimiterService).setTimeProvider(() -> activeTime);
        }

        try {
            // Send requests equal to the configured limit: they should all succeed (200 OK)
            for (int i = 1; i <= configuredLimit; i++) {
                mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                        .andExpect(status().isOk());
            }

            // The next request should be blocked (429 Too Many Requests)
            mockMvc.perform(get("/api/test").header("X-Customer-Id", customerId))
                    .andExpect(status().isTooManyRequests());
        } finally {
            // Restore default time provider
            if (rateLimiterService instanceof RedisTokenBucketRateLimiter) {
                ((RedisTokenBucketRateLimiter) rateLimiterService).setTimeProvider(LocalTime::now);
            }
        }
    }
}

