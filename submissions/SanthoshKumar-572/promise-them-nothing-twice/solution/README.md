# Enterprise Distributed Rate Limiter

A production-grade, highly-scalable distributed rate limiting middleware built with **Java 21**, **Spring Boot 3**, and **Redis** for an enterprise API gateway.

The middleware implements the **Token Bucket Algorithm** using atomic **Redis Lua scripting**, guaranteeing accuracy and synchronization across multiple Spring Boot application instances.

---

## Architecture

The system uses a clean, layered architecture to process rate limit checks before requests reach downstream REST controllers:

```
  HTTP Client Request
         │
         ▼
  RateLimitFilter (Servlet Filter / OncePerRequestFilter)
         │
         ├── [X-Customer-Id Validation]
         │     ├── Empty -> 400 Bad Request
         │     └── Unknown -> 401 Unauthorized
         ▼
  RedisTokenBucketRateLimiter (RateLimiterService)
         │
         ├── [Load Configured Limit from application.yml]
         ├── [Compute current token bucket state]
         ▼
  Redis Server (Atomic Lua Script Execution)
         │
         ├── Check tokens & update bucket state (tokens, last_updated)
         └── Return results (allowed: true/false, remaining, elapsed)
         │
         ▼
  Allowed?
    ├── YES -> Append X-RateLimit-Limit & X-RateLimit-Remaining -> Forward to Controller
    └── NO  -> Return 429 Too Many Requests {"error": "Too Many Requests"}
```

---

## Token Bucket Algorithm

1. **State Storage**: Each customer has a dedicated token bucket represented by a Redis Hash containing:
   - `tokens`: Current count of available tokens (floating point stored as string in Redis).
   - `last_updated`: Epoch millisecond timestamp of the last request evaluation.
2. **Refill Calculation**: Upon receiving a request, the time elapsed since `last_updated` is calculated:
   $$\Delta T = T_{\text{now}} - T_{\text{last\_updated}}$$
   Tokens are refilled dynamically:
   $$\text{refilled\_tokens} = \min(\text{Capacity}, \text{tokens} + \Delta T \times \text{RefillRate})$$
   Where $\text{RefillRate} = \frac{\text{Limit}}{60000}$ tokens/millisecond (representing Requests Per Minute).
3. **Consumption**: If $\text{refilled\_tokens} \ge 1$, the request is permitted, `tokens` is decremented by 1, and `last_updated` is set to $T_{\text{now}}$. Otherwise, the request is rejected with `HTTP 429`.
4. **Atomicity**: The entire check-refill-consume transaction is executed in a single Redis Lua script (`rate_limiter.lua`), avoiding concurrency race conditions between parallel requests across multiple server instances.

---

## Project Structure

```
src/main/java
└── com/enterprise/ratelimiter
    ├── Application.java             # Entry point
    ├── config
    │   ├── RateLimitProperties.java # Binds customer configuration limits
    │   └── RedisConfig.java         # Configures Redis templates & Lua script beans
    ├── controller
    │   └── RateLimitTestController.java # Test REST endpoint (GET /api/test)
    ├── filter
    │   └── RateLimitFilter.java     # OncePerRequestFilter for gatekeeping requests
    ├── model
    │   ├── ErrorResponse.java       # Standard Error JSON record
    │   └── RateLimitResult.java     # Internal rate limiting outcome representation
    ├── service
    │   ├── RateLimiterService.java  # Limit evaluation interface
    │   └── RedisTokenBucketRateLimiter.java # Core service invoking Redis
    └── exception
        ├── RateLimitException.java  # Parent exception
        ├── MissingHeaderException.java # 400 Bad Request exception
        ├── UnknownCustomerException.java # 401 Unauthorized exception
        └── RateExceededException.java # 429 Too Many Requests exception
```

---

## Setup & Running Instructions

### 1. Prerequisites
- Docker & Docker Compose
- Java 21 or later
- Maven (or portable Maven Wrapper provided in standard systems)

### 2. Running Redis
Start the Redis server using Docker Compose:
```bash
docker-compose up -d
```
This launches a Redis server container bound to port `6379`.

### 3. Running Spring Boot
To compile and run the application locally on port `8080`:
```bash
# Compile and package
mvn clean package

# Start the Spring Boot instance
java -jar target/rate-limiter-1.0.0.jar
```

To run **multiple instances** locally, specify different ports:
```bash
java -jar target/rate-limiter-1.0.0.jar --server.port=8080
java -jar target/rate-limiter-1.0.0.jar --server.port=8081
java -jar target/rate-limiter-1.0.0.jar --server.port=8082
```
All three instances will connect to the same Redis instance on port `6379`.

---

## Testing

### Automated Tests
Run unit and integration tests using Maven:
```bash
mvn test
```
*Note: The Integration tests automatically check if Redis is running locally on port 6379. If Redis is unreachable, they are skipped dynamically to ensure that raw compilation builds succeed.*

---

## API Examples

### 1. Request Allowed (200 OK)
```bash
curl -i -H "X-Customer-Id: starter-company" http://localhost:8080/api/test
```
**Response Headers**:
```http
HTTP/1.1 200 OK
Content-Type: application/json
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 59
```
**Response Body**:
```json
{"message":"Success"}
```

### 2. Rate Limit Exceeded (429 Too Many Requests)
Once the customer exceeds their configured limit (e.g. 60 requests in under a minute for `starter-company`):
```bash
curl -i -H "X-Customer-Id: starter-company" http://localhost:8080/api/test
```
**Response Headers**:
```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json;charset=UTF-8
```
**Response Body**:
```json
{"error":"Too Many Requests"}
```

### 3. Unknown Customer (401 Unauthorized)
```bash
curl -i -H "X-Customer-Id: invalid-customer" http://localhost:8080/api/test
```
**Response Headers**:
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=UTF-8
```
**Response Body**:
```json
{"error":"Unknown customer"}
```

### 4. Missing Header (400 Bad Request)
```bash
curl -i http://localhost:8080/api/test
```
**Response Headers**:
```http
HTTP/1.1 400 Bad Request
Content-Type: application/json;charset=UTF-8
```
**Response Body**:
```json
{"error":"Missing customer header"}
```

---

## Design Decisions
See [DECISIONS.md](DECISIONS.md) for architectural trade-offs, multi-instance synchronization details, and future scalability improvements.
