--比较线程标识与锁标识是否相同，相同则删除锁
if (redis.call("GET", KEYS[1]) == ARGV[1]) then
    return redis.call("DEL", KEYS[1])
end
return 0