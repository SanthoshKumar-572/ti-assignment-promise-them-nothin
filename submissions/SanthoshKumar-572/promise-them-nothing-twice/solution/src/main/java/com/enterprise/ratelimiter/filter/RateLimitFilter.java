package com.enterprise.ratelimiter.filter;

import com.enterprise.ratelimiter.exception.MissingHeaderException;
import com.enterprise.ratelimiter.exception.RateExceededException;
import com.enterprise.ratelimiter.exception.UnknownCustomerException;
import com.enterprise.ratelimiter.model.ErrorResponse;
import com.enterprise.ratelimiter.model.RateLimitResult;
import com.enterprise.ratelimiter.model.RateLimitResponse;
import com.enterprise.ratelimiter.service.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiterService rateLimiterService, ObjectMapper objectMapper) {
        this.rateLimiterService = rateLimiterService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip rate limiting for the global error path, status API, and static frontend dashboard
        return "/error".equals(path)
                || "/api/customers/status".equals(path)
                || "/".equals(path)
                || "/index.html".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String customerId = request.getHeader(CUSTOMER_HEADER);
        RateLimitResult result = null;

        try {
            if (customerId == null || customerId.trim().isEmpty()) {
                throw new MissingHeaderException("Missing customer header");
            }

            result = rateLimiterService.checkRateLimit(customerId);

            // Append standard rate limiting metadata headers to the response
            response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
            response.setHeader("X-RateLimit-Policy", result.policyName());

            if (!result.allowed()) {
                long waitTimeMs = result.waitTimeMs();
                int retryAfterSeconds = (int) Math.ceil(waitTimeMs / 1000.0);
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                throw new RateExceededException("Too Many Requests");
            }

            request.setAttribute("customerName", customerId);
            request.setAttribute("allocated", (long) result.limit());
            long remaining = result.remainingTokens();
            long used = remaining == -1 ? 0L : (result.limit() - remaining);
            request.setAttribute("used", used);
            request.setAttribute("status", "✔");

            filterChain.doFilter(request, response);

        } catch (MissingHeaderException ex) {
            log.warn("Rate limit filter error: {}", ex.getMessage());
            writeJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, 
                    new RateLimitResponse("Missing", 0L, 0L, "❌"));
        } catch (UnknownCustomerException ex) {
            log.warn("Rate limit filter error: {}", ex.getMessage());
            writeJsonResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                    new RateLimitResponse(customerId, 0L, 0L, "❌"));
        } catch (RateExceededException ex) {
            log.warn("Rate limit filter error: {}", ex.getMessage());
            long limit = result != null ? result.limit() : 0L;
            long remaining = result != null ? result.remainingTokens() : 0L;
            long used = result != null ? (limit - remaining) : 0L;
            writeJsonResponse(response, 429, 
                    new RateLimitResponse(customerId, limit, used, "❌"));
        } catch (Exception ex) {
            log.error("Unexpected error in rate limiting filter", ex);
            writeJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                    new RateLimitResponse(customerId != null ? customerId : "Unknown", 0L, 0L, "❌"));
        }
    }

    private void writeJsonResponse(HttpServletResponse response, int status, Object responseBody) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String jsonBody = objectMapper.writeValueAsString(responseBody);

        response.getWriter().write(jsonBody);
        response.getWriter().flush();
    }
}
