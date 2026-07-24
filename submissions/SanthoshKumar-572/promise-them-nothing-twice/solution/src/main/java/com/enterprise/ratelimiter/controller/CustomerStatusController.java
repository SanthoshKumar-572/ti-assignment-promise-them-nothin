package com.enterprise.ratelimiter.controller;

import com.enterprise.ratelimiter.model.CustomerStatusDTO;
import com.enterprise.ratelimiter.service.CustomerStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerStatusController {

    private final CustomerStatusService customerStatusService;

    public CustomerStatusController(CustomerStatusService customerStatusService) {
        this.customerStatusService = customerStatusService;
    }

    @GetMapping("/status")
    public ResponseEntity<List<CustomerStatusDTO>> getCustomersStatus() {
        return ResponseEntity.ok(customerStatusService.getCustomersStatus());
    }
}
