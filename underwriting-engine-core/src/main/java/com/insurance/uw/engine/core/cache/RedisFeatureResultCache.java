package com.insurance.uw.engine.core.cache;

import com.insurance.uw.engine.core.service.FeatureResultCache;

import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis 的特征计算结果缓存实现
 */
public class RedisFeatureResultCache implements FeatureResultCache {

    static final String PREFIX = "uw:result:";

    private final CacheOps cache;

    public RedisFeatureResultCache(CacheOps cache) {
        this.cache = cache;
    }

    static String resultKey(String featureCode, String targetId) {
        return PREFIX + featureCode + ":" + targetId;
    }

    @Override
    public Optional<Object> get(String featureCode, String targetId) {
        return cache.getIfPresent(resultKey(featureCode, targetId), Object.class);
    }

    @Override
    public void put(String featureCode, String targetId, Object value, int ttlSeconds) {
        cache.set(resultKey(featureCode, targetId), value, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public void evictAll() {
        cache.deleteByPrefix(PREFIX);
    }
}
