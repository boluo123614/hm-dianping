-- 1.参数列表
-- 1.1优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.库存判断
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 4.用户判断
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已购买过
    return 2
end

-- 5.扣减库存
redis.call('incrby', stockKey, -1)

-- 6.下单
redis.call('sadd', orderKey, userId)

return 0