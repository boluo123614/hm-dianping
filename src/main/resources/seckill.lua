-- 请求线程调用本脚本，在 Redis 中判断并预留购买资格；这里不会创建 MySQL 订单。
-- Redis 执行脚本时不会穿插其他请求的命令，避免多个用户同时看到最后一件库存并都扣减。
-- 返回码：0 = 资格通过，1 = 库存不足，2 = 重复购买。

-- 1.参数列表：ARGV 对应 Java execute 方法中 KEYS 列表后面的参数，Lua 下标从 1 开始。
-- 1.1优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]
-- 1.3订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1库存 key，类型为 String，例如 seckill:stock:13 → "100"；.. 是 Lua 的字符串拼接运算符。
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2购买资格 key，类型为 Set，例如 seckill:order:13 中保存已取得资格的用户 ID。
-- 这个集合不保存完整订单，也不表示对应订单已经成功写入 MySQL。
local orderKey = 'seckill:order:' .. voucherId

-- 3.GET 读到的是字符串，tonumber 转成数字后再比较；库存必须先由添加秒杀券的代码初始化。
-- 当前脚本没有处理库存 key 不存在的情况，也没有检查秒杀开始、结束时间。
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 4.SISMEMBER 返回 1 表示该用户已在集合中，返回 0 表示尚未登记；每张券各有一个用户集合。
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已购买过
    return 2
end

-- 5.资格通过，INCRBY -1 预扣一件 Redis 库存，后续请求将看到减少后的库存。
redis.call('incrby', stockKey, -1)

-- 6.SADD 登记当前用户，阻止其再次取得同一优惠券的购买资格；这一步尚未执行数据库下单。
redis.call('sadd', orderKey, userId)

-- 7.发送订单消息到 Redis Stream
redis.call('xadd', 'stream.orders', '*', 'voucherId', voucherId, 'userId', userId, 'id', orderId)

-- Java 收到 0 后会生成订单 ID、提交内存队列，再由后台线程下单。
-- 后续入队或数据库操作失败，不会自动撤销这里的 Redis 库存扣减和用户登记。
return 0