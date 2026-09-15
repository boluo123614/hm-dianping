package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //1.先从缓存中获取
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //2.命中，JSON 数组转回 List 直接返回
        if (StrUtil.isNotBlank(typeJson)) {
            return JSONUtil.toList(typeJson, ShopType.class);
        }
        //3.未命中，查询数据库，按sort升序
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4.表里没有数据-返回空集合
        if (typeList == null || typeList.isEmpty()) {
            return Collections.emptyList();
        }
        //5.整个 List 序列化成一个 JSON 数组写回缓存
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList),
                        CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return typeList;
    }
}
