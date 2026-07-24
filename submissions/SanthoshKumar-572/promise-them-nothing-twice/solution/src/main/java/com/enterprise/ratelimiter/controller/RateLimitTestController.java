package com.enterprise.ratelimiter.controller;

import com.enterprise.ratelimiter.model.RateLimitResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RateLimitTestController {

    @GetMapping("/test")
    public ResponseEntity<RateLimitResponse> testEndpoint(HttpServletRequest request) {
        String customerName = (String) request.getAttribute("customerName");
        Long allocated = (Long) request.getAttribute("allocated");
        Long used = (Long) request.getAttribute("used");
        String status = (String) request.getAttribute("status");

        return ResponseEntity.ok(new RateLimitResponse(
                customerName != null ? customerName : "Unknown",
                allocated != null ? allocated : 0L,
                used != null ? used : 0L,
                status != null ? status : "✔"
        ));
    }
}
