package com.enterprise.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "")
public class RateLimitProperties {

    private Map<String, CustomerConfig> customers = new HashMap<>();

    public Map<String, CustomerConfig> getCustomers() {
        return customers;
    }

    public void setCustomers(Map<String, CustomerConfig> customers) {
        this.customers = customers;
    }

    public static class CustomerConfig {
        private int limit;
        private List<ScheduledLimit> schedules = new ArrayList<>();

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public List<ScheduledLimit> getSchedules() {
            return schedules;
        }

        public void setSchedules(List<ScheduledLimit> schedules) {
            this.schedules = schedules;
        }
    }

    public static class ScheduledLimit {
        private String name;
        private String startTime; // format "HH:mm"
        private String endTime;   // format "HH:mm"
        private int limit;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStartTime() {
            return startTime;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getEndTime() {
            return endTime;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}

