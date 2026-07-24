# Architectural Decisions & Design Trade-offs

This document outlines the engineering decisions and design trade-offs made during the implementation of the Enterprise Distributed Rate Limiter.

---

## 1. Why Token Bucket?

We chose the **Token Bucket** algorithm over other rate-limiting options (such as Fixed Window, Sliding Window Log, or Leaky Bucket) for several reasons:

### Advantages:
*   **Smooth Handling of Bursts**: The Token Bucket algorithm allows clients to burst up to the capacity limit when tokens are fully available, but throttles sustained traffic to the configured refill rate. This is highly suitable for enterprise APIs where traffic can be spikey but needs strict maximum limits.
*   **Memory Efficiency**: Fixed and sliding window algorithms (especially sliding window logs) require storing timestamps for every single request in a window, leading to heavy memory usage at high throughput. Token Bucket only requires storing two fields per bucket (`tokens` and `last_updated`), making its memory footprint constant ($O(1)$) regardless of request volume.
*   **Refill Flexibility**: Unlike fixed window limits, which refill all at once at the boundary (causing "thundering herd" patterns where clients burst at the turn of the minute), Token Bucket refills tokens incrementally on every request based on elapsed time, producing smooth, continuous throttling.

---

## 2. Why Redis?

For a distributed architecture with multiple Spring Boot instances, local in-memory rate limiting (e.g. using Caffeine or Guava) is insufficient because requests from the same customer could hit different servers.

We chose **Redis** as the centralized state store because:
*   **High Performance**: Redis is an in-memory database that operates at sub-millisecond latencies, which is critical for gateway rate limiters that add overhead to every API request.
*   **Atomic Lua Scripting**: A standard check-and-set database operation introduces race conditions (e.g., two parallel requests checking tokens, seeing 1 token left, and both proceeding). Redis executes Lua scripts as a single atomic operation on its main execution thread, preventing race conditions without needing costly distributed locks.
*   **Key Expiry**: Redis supports TTL (Time-To-Live) on keys. By putting an expiration on customer buckets (e.g., 24 hours), inactive customer records are automatically purged, preventing memory leaks.

---

## 3. Why Middleware?

We chose to implement the rate limiter as a **Spring Boot Filter (OncePerRequestFilter)** rather than controller annotations or a service interceptor.

### Rationale:
*   **Fail-Fast Security**: By rejecting unauthorized (401), malformed (400), or rate-exceeded (429) requests at the servlet filter level, the request is intercepted *before* routing, JSON parsing, validation, or Spring controller context initialization occurs. This preserves resources and protects the application from CPU exhaustion during DDoS attacks.
*   **Decoupled Design**: Downstream API controllers can be written as standard REST endpoints without needing any awareness of rate limiting logic.

---

## 4. Why Configuration instead of Hardcoded Logic?

We configured customer limits dynamically using `application.yml` bound to properties classes:
*   **Zero-Code Changes**: Customer plan upgrades or limit modifications only require configuration changes. In a Kubernetes or production environment, these can be injected via config maps, database mappings, or dynamic configuration servers (e.g., Spring Cloud Config) without rebuilding or redeploying code.
*   **SOLID Compliance**: Hardcoding customer checks violates the **Open-Closed Principle (OCP)**, requiring class modification whenever a customer is added or updated. Our configuration-driven design is open for extension but closed for modification.

---

## 5. How Multiple Servers Remain Synchronized

*   **Shared Redis Store**: All instances of the Spring Boot application read and write from the same Redis server.
*   **Consistent Time Standard**: Because token refill calculations depend on timestamps, clocks across Spring Boot instances must be synchronized (typically via NTP). To mitigate minor clock drifts, the Lua script uses the Unix time supplied by the client application, but we can also use Redis's internal time command `TIME` within Lua if complete isolation from server clock drift is required. (In this implementation, we pass the server's `System.currentTimeMillis()`, which is standard, and we rely on standard container/VM clock synchronization).

---

## 6. Trade-offs Considered

### Network Hop vs. Local Speed
*   **Trade-off**: Every API request incurs a network round-trip to Redis before response rendering.
*   **Mitigation**: Standard Redis instances process requests in $<1$ ms. If latency becomes critical at extreme scales, a hybrid approach (using short-term local cache with periodic Redis synchronization) can be used, though this introduces a small rate-limiting inaccuracy window. For strict security, the direct Redis Lua script approach is preferred.

### Redis Single Point of Failure (SPOF)
*   **Trade-off**: If Redis is offline, the filter might block all incoming requests (fail-closed) or allow all requests unchecked (fail-open).
*   **Decision**: In our filter exception handling, any unexpected error (such as Redis connection timeout) results in an HTTP 500 error. In production, this can be wrapped with a circuit breaker (e.g., Resilience4j) to fail-open (allowing traffic but logging alerts) if the API Gateway must maintain availability during cache downtime.

---

## 7. Future Improvements

1. **Redis Cluster Support**: For massive scale, use Redis Cluster and distribute customer keys using hash tags (e.g. `{customer:northwind}`) to scale redis nodes horizontally.
2. **Dynamic DB Loading**: Integrate with a database or caching layer to dynamically load customer configurations so that new customers can be registered without server restarts.
3. **Resilience & Fail-Open Fallback**: Implement a fallback mechanism so that if Redis encounters a connection issue, the gateway transitions to a temporary state (e.g. relying on local memory or failing open) to avoid completely dropping customer traffic.
4. **Header Improvements**: Add custom headers indicating when the bucket will fully refill (e.g. `Retry-After`).
