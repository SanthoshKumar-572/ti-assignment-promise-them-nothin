# AI Working Session 01 - Requirements & Design

This session covers the initial discussion of the rate limiter requirements, alignment on the CTO's rules, comparison of rate limiting algorithms, environment setup (Maven), and future extensibility ideas.

## User
Discuss the requirements for the Enterprise Distributed Rate Limiter based on CTO Priya's instructions and Marcus Webb's Support memo.

## Agent Response
### CTO Priya's Key Requirements:
1. **Limit Must Never Be Exceeded**: If a customer has a limit of 100 requests/minute, they can send up to 100 requests. The 101st request must be rejected immediately with an HTTP 429 Too Many Requests response. (Water metaphor: after 100 liters, the tap closes automatically).
2. **Customer Limits Isolation**: Customer A's limit usage must not affect Customer B's limit. Each customer has their own rate limit and bucket state (separate water meters).
3. **No Special Treatment**: Do not write special-case code for specific customers (e.g., `if (customer == "Northwind") { limit = 1000; }`). All limits must be configuration-driven, not hardcoded.
4. **Multi-Server Synchronization**: In a multi-server setup (e.g., 3 Spring Boot instances), the aggregate requests across all servers must not exceed the limit. For example, if Server 1 processes 40, Server 2 processes 35, and Server 3 processes 25, the total is 100; any subsequent request to any server must be rejected.
5. **Strict Safety/Fail-Closed**: If servers are out of sync or there's an error, prefer rate limiting strictly (e.g. blocking at 98 requests rather than allowing 102). "Better to block a bit earlier than to let them exceed the limit."
6. **Use Standard Algorithms**: Do not design custom rate-limiting algorithms. Use well-established ones such as Token Bucket, Leaky Bucket, or Sliding Window.

### How the CTO Tests the Solution:
- Deploys 3 servers.
- Configures 2 customers (Customer A limit = 100, Customer B limit = 100).
- Sends random requests distributed across all 3 servers.
- Verifies that both Customer A and Customer B are capped at exactly 100 requests, and any extra request immediately receives a 429 Too Many Requests response.

---

## User
next

## Agent Response
Acknowledged. Let's move on to the next set of requirements (e.g. the Support Lead Marcus Webb's memo). Please provide the memo or paste its content so we can compare it with the CTO's requirements.

---

## User
Algorithm, we can use token budget, leaky budget, or sliding window.

## Agent Response
Here is a comparison of the rate limiting algorithms discussed:

| Algorithm | How it Works | Key Advantages |
| :--- | :--- | :--- |
| **Token Bucket** | Tokens refill in a bucket at a set rate. Each request consumes one token. Requests are rejected if the bucket is empty. | Standard, handles burst traffic gracefully, highly efficient for distributed systems. |
| **Leaky Bucket** | Requests are queued and processed at a constant rate. Excess requests overflow the queue and are dropped. | Smooths traffic peaks, ensures constant output rate. |
| **Sliding Window** | Tracks timestamps of requests within a sliding window (e.g., last 60 seconds). | Extremely accurate, avoids boundary reset bursts. |

### Recommendation for this Assignment:
For this distributed rate limiter, the **Token Bucket** algorithm is the most suitable because it is standard in production environments, easily scales across distributed systems using Redis atomic operations (Lua script), and allows graceful handling of bursty traffic without unnecessary request delays.

---

## User
iam not install manually so only iam saking u give me cmd

## Agent Response
Here are the automated commands to set up Maven on Windows:

### Method 1: Using Winget (Windows Package Manager)
```powershell
winget search Maven
winget install --id Apache.Maven
```

### Method 2: Using Chocolatey
```powershell
choco install maven -y
```

### Method 3: Using Scoop
```powershell
scoop install maven
```

### Alternative: Using Maven Wrapper (Built-in)
If your project folder already includes a Maven wrapper, you do not need a system-wide Maven installation. Simply run commands directly from the project directory:
```powershell
.\mvnw.cmd spring-boot:run
```

---

## User
now i want to notifiy the customer la before 4 token s la

## Agent Response
This is a great production-ready enhancement. If a customer is close to hitting their rate limit (e.g., when they have fewer than 4 tokens remaining, or when their temporary rate limit window is about to expire), we can notify them to adjust their throughput.

### Implementation Options:
1. **HTTP Headers**: Add custom warning headers to the response, e.g.:
   ```http
   X-RateLimit-Limit: 1200
   X-RateLimit-Remaining: 3
   Warning: 199 - "Rate limit warning: Less than 4 tokens remaining"
   ```
2. **Response Body JSON**: For JSON APIs, include a warning field in the metadata:
   ```json
   {
     "message": "Success",
     "warning": "You have fewer than 4 tokens remaining in your bucket."
   }
   ```

### Assignment Scope Note:
While not strictly required by the core criteria of the assignment, documenting this proactive warning strategy under "Future Improvements" in `DECISIONS.md` demonstrates advanced engineering thinking and production readiness.