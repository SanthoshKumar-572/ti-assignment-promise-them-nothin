package com.enterprise.ratelimiter;

import com.enterprise.ratelimiter.model.Customer;
import com.enterprise.ratelimiter.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class CustomerStatusControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();
        customerRepository.save(new Customer("test-starter", "STARTER", 100, null, null));
        customerRepository.save(new Customer("test-enterprise", "ENTERPRISE", 500, 1500, "02:00-04:00"));
    }

    @Test
    void testGetCustomersStatus() throws Exception {
        mockMvc.perform(get("/api/customers/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[?(@.customerId == 'test-starter')].plan").value("STARTER"))
                .andExpect(jsonPath("$[?(@.customerId == 'test-starter')].normalLimit").value(100))
                .andExpect(jsonPath("$[?(@.customerId == 'test-enterprise')].plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$[?(@.customerId == 'test-enterprise')].specialLimit").value(1500));
    }
}
