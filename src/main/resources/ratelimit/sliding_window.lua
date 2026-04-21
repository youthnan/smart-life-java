-- 滑动窗口计数：KEYS[1]=限流 Redis key；ARGV[1]=窗口毫秒；ARGV[2]=窗口内最大次数；ARGV[3]=当前时间戳(毫秒)；ARGV[4]=本次请求唯一 member
local key = KEYS[1]
local windowMs = tonumber(ARGV[1])
local max = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local member = ARGV[4]
local minScore = now - windowMs
redis.call('zremrangebyscore', key, '-inf', minScore)
redis.call('zadd', key, now, member)
local c = redis.call('zcard', key)
if c > max then
  redis.call('zrem', key, member)
  return 0
end
redis.call('pexpire', key, windowMs)
return 1
