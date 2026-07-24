-- KEYS[1]: Redis key for customer bucket (e.g. "rate_limit:customers:starter-company")
-- ARGV[1]: Capacity of the bucket (e.g. 60)
-- ARGV[2]: Refill rate in tokens per millisecond (e.g. 60 / 60000.0 = 0.001)
-- ARGV[3]: Current timestamp in milliseconds
-- ARGV[4]: Cost of request (usually 1)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4] or 1)

local bucket = redis.call('HMGET', key, 'tokens', 'last_updated')
local tokens = tonumber(bucket[1])
local last_updated = tonumber(bucket[2])
local elapsed = -1

if tokens == nil or last_updated == nil then
    -- Bucket initialization
    tokens = capacity
    last_updated = now
else
    elapsed = now - last_updated
    if elapsed > 0 then
        local refill = elapsed * refill_rate
        tokens = math.min(capacity, tokens + refill)
        last_updated = now
    else
        elapsed = 0
    end
end

local allowed = 0
local wait_time_ms = 0
if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
else
    -- Even when request is rejected, we save the refilled state to ensure progress is tracked
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
    if refill_rate > 0 then
        wait_time_ms = math.ceil((requested - tokens) / refill_rate)
    end
end

redis.call('EXPIRE', key, 86400) -- Expire key after 24 hours of inactivity to prevent leaks

return {allowed, math.floor(tokens), elapsed, wait_time_ms}
