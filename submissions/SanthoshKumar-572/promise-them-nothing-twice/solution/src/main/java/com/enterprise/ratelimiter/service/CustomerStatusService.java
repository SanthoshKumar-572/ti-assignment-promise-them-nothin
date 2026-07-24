package com.enterprise.ratelimiter.service;

import com.enterprise.ratelimiter.model.Customer;
import com.enterprise.ratelimiter.model.CustomerStatusDTO;
import com.enterprise.ratelimiter.repository.CustomerRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
public class CustomerStatusService {

    private final CustomerRepository customerRepository;
    private final StringRedisTemplate stringRedisTemplate;

    private Supplier<LocalTime> timeProvider = LocalTime::now;
    private Supplier<Long> epochTimeProvider = System::currentTimeMillis;

    public CustomerStatusService(CustomerRepository customerRepository, StringRedisTemplate stringRedisTemplate) {
        this.customerRepository = customerRepository;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void setTimeProvider(Supplier<LocalTime> timeProvider) {
        this.timeProvider = timeProvider;
    }

    public void setEpochTimeProvider(Supplier<Long> epochTimeProvider) {
        this.epochTimeProvider = epochTimeProvider;
    }

    public List<CustomerStatusDTO> getCustomersStatus() {
        List<Customer> customers = customerRepository.findAll();
        List<CustomerStatusDTO> statuses = new ArrayList<>();

        LocalTime currentTime = timeProvider.get();
        long now = epochTimeProvider.get();

        for (Customer customer : customers) {
            int activeLimit = customer.getNormalLimit();
            String policyType = "NORMAL";

            boolean isSpecialActive = false;
            if (customer.getSpecialLimit() != null && customer.getSpecialWindow() != null) {
                isSpecialActive = isWithinWindow(customer.getSpecialWindow(), currentTime);
                if (isSpecialActive) {
                    activeLimit = customer.getSpecialLimit();
                    if ("northwind".equals(customer.getCustomerId())) {
                        policyType = "NIGHT_BATCH_POLICY";
                    } else {
                        policyType = "SPECIAL";
                    }
                }
            }

            long remaining = getRemainingTokens(customer, activeLimit, now);
            String status = remaining > 0 ? "✔" : "❌";

            statuses.add(new CustomerStatusDTO(
                    customer.getCustomerId(),
                    customer.getPlan(),
                    customer.getNormalLimit(),
                    customer.getSpecialLimit(),
                    activeLimit,
                    customer.getSpecialWindow(),
                    remaining,
                    status,
                    policyType
            ));
        }

        return statuses;
    }

    private boolean isWithinWindow(String window, LocalTime time) {
        if (window == null || window.isEmpty()) {
            return false;
        }
        try {
            String[] parts = window.split("-");
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());

            if (start.isBefore(end)) {
                return !time.isBefore(start) && !time.isAfter(end);
            } else {
                return !time.isBefore(start) || !time.isAfter(end);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private long getRemainingTokens(Customer customer, int activeLimit, long now) {
        String redisKey = "rate_limit:customers:" + customer.getCustomerId();
        try {
            List<Object> fields = stringRedisTemplate.opsForHash().multiGet(redisKey, List.of("tokens", "last_updated"));

            if (fields == null || fields.size() < 2 || fields.get(0) == null || fields.get(1) == null) {
                return activeLimit;
            }

            double tokens = Double.parseDouble((String) fields.get(0));
            long lastUpdated = Long.parseLong((String) fields.get(1));

            long elapsed = now - lastUpdated;
            if (elapsed > 0) {
                double refillRate = (double) activeLimit / 60000.0;
                double refill = elapsed * refillRate;
                tokens = Math.min(activeLimit, tokens + refill);
            }
            return (long) Math.floor(tokens);
        } catch (Exception e) {
            // Return full capacity if Redis is offline
            return activeLimit;
        }
    }
}
