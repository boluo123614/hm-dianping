package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class RedisIdWorker {

    private static final long Begin_TimeSTAMP = 1640995200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyprefix) {
        // 获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - Begin_TimeSTAMP;
        // 生成序列号
        String date = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyprefix + ":"+date);
        return timestamp << 32 | count;
    }
}
