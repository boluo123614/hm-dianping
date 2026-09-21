package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String key_prefix = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = Thread.currentThread().getId() + "";
        String key = key_prefix + name;
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //删除锁
        stringRedisTemplate.delete(key_prefix + name);
    }
}
