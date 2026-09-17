package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 7.返回
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String cacheKey = CACHE_SHOP_KEY + id;
        String lockKey = "lock:shop:" + id;

        // 最多重试 10 次，避免无限等待
        for (int i = 0; i < 10; i++) {
            // 1. 查询缓存
            String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

            // 有正常缓存
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 有空值缓存：数据库中不存在该店铺
            if (shopJson != null) {
                return null;
            }

            // 2. 尝试获取锁
            boolean isLocked = tryLock(lockKey);
            if (!isLocked) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                continue;
            }

            try {
                // 3. 获取锁后再次检查缓存
                // 可能别的线程刚刚已经完成缓存重建
                shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

                if (StrUtil.isNotBlank(shopJson)) {
                    return JSONUtil.toBean(shopJson, Shop.class);
                }

                if (shopJson != null) {
                    return null;
                }

                // 4. 查询数据库并重建缓存
                Shop shop = getById(id);

                if (shop == null) {
                    long nullTtl = CACHE_NULL_TTL + RandomUtil.randomInt(1, 3);
                    stringRedisTemplate.opsForValue().set(
                            cacheKey, "", nullTtl, TimeUnit.MINUTES
                    );
                    return null;
                }

                long ttl = CACHE_SHOP_TTL + RandomUtil.randomInt(1, 6);
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        JSONUtil.toJsonStr(shop),
                        ttl,
                        TimeUnit.MINUTES
                );
                return shop;
            } finally {
                // 只由真正拿到锁的线程释放
                unlock(lockKey);
            }
        }

        // 多次重试仍未获得结果，可按项目规范抛异常或降级
        throw new RuntimeException("当前请求繁忙，请稍后重试");
    }

    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.根据id从redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            return null;
        }
        // 4.不存在，根据id从数据库中查询
        Shop shop = getById(id);
        // 5.不存在，返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入 redis：基础 TTL + 随机过期时间，避免缓存雪崩
        int randomTtl = RandomUtil.randomInt(1, 6); // [1, 6)，即 1~5 分钟
        long ttl = CACHE_SHOP_TTL + randomTtl;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }

    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
