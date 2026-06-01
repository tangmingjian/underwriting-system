package com.insurance.uw.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 缓存操作工具 — 基于 cache-aside 模式的读/写/失效封装。
 *
 * 使用 StringRedisTemplate + Jackson 序列化，实体无需实现 Serializable。
 * Key 命名空间：uw:fc / uw:rule / uw:script
 */
public class CacheOps {

    private static final Logger log = LoggerFactory.getLogger(CacheOps.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CacheOps(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ==================== GET ====================

    /**
     * 查单个对象：命中返回，未命中调用 loader 加载并回写缓存。
     */
    public <T> Optional<T> get(String key, Class<T> type, Supplier<Optional<T>> loader, Duration ttl) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                log.debug("[Cache] hit: {}", key);
                return Optional.of(objectMapper.readValue(json, type));
            }
        } catch (Exception e) {
            log.warn("[Cache] 反序列化失败 key={}: {}", key, e.getMessage());
        }

        log.debug("[Cache] miss: {}", key);
        Optional<T> result = loader.get();
        result.ifPresent(value -> set(key, value, ttl));
        return result;
    }

    /**
     * 查列表：命中返回，未命中调用 loader 加载并回写缓存。
     */
    public <T> List<T> getList(String key, Class<T> elementType,
                                Supplier<List<T>> loader, Duration ttl) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                log.debug("[Cache] hit list: {}", key);
                return objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
            }
        } catch (Exception e) {
            log.warn("[Cache] 反序列化 List 失败 key={}: {}", key, e.getMessage());
        }

        log.debug("[Cache] miss list: {}", key);
        List<T> result = loader.get();
        set(key, result, ttl);
        return result;
    }

    // ==================== SET / EVICT ====================

    public void set(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化失败 key={}: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        redis.delete(key);
        log.debug("[Cache] evict: {}", key);
    }

    // ==================== Key 构建 ====================

    public static final String PREFIX_FC     = "uw:fc:";
    public static final String PREFIX_RULE   = "uw:rule:";
    public static final String PREFIX_SCRIPT = "uw:script:";

    public static String fcKey(String featureCode)        { return PREFIX_FC + featureCode; }
    public static String fcAllKey()                        { return PREFIX_FC + "__ALL__"; }
    public static String ruleKey(String ruleCode)          { return PREFIX_RULE + ruleCode; }
    public static String ruleAllKey()                      { return PREFIX_RULE + "__ALL__"; }
    public static String scriptKey(String scriptId)        { return PREFIX_SCRIPT + scriptId; }
    public static String scriptAllKey()                    { return PREFIX_SCRIPT + "__ALL__"; }

}
