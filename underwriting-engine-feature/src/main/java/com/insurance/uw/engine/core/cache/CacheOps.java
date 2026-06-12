package com.insurance.uw.engine.core.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * 仅查缓存，不触发加载和回写。
     */
    public <T> Optional<T> getIfPresent(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                log.debug("[Cache] hit: {}", key);
                return Optional.of(objectMapper.readValue(json, type));
            }
        } catch (Exception e) {
            log.warn("[Cache] 反序列化失败 key={}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

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

    // ==================== MULTI GET ====================

    /**
     * 批量获取（使用 Redis MGET），返回与 keys 一一对应的 Optional 列表。
     * 未命中的位置返回 Optional.empty()。
     */
    public <T> List<Optional<T>> multiGet(List<String> keys, Class<T> type) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        try {
            List<String> jsons = redis.opsForValue().multiGet(keys);
            if (jsons == null) {
                return keys.stream().map(k -> Optional.<T>empty()).collect(java.util.stream.Collectors.toList());
            }
            List<Optional<T>> results = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                String json = jsons.get(i);
                if (json != null && !json.isEmpty()) {
                    try {
                        results.add(Optional.of(objectMapper.readValue(json, type)));
                    } catch (Exception e) {
                        log.warn("[Cache] multiGet 反序列化失败 key={}: {}", keys.get(i), e.getMessage());
                        results.add(Optional.empty());
                    }
                } else {
                    results.add(Optional.empty());
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("[Cache] multiGet 失败: {}", e.getMessage());
            return keys.stream().map(k -> Optional.<T>empty()).collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * 批量写回（MSET），用于回填缓存。
     */
    public <T> void multiSet(Map<String, T> entries, Duration ttl) {
        if (entries == null || entries.isEmpty()) return;
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (var entry : entries.entrySet()) {
                map.put(entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
            }
            redis.opsForValue().multiSet(map);
            // Redis MSET 不支持直接设置 TTL，逐 key 设置
            for (String key : entries.keySet()) {
                redis.expire(key, ttl);
            }
        } catch (Exception e) {
            log.warn("[Cache] multiSet 失败: {}", e.getMessage());
        }
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

    /**
     * 按前缀批量删除缓存 key，基于 SCAN 迭代避免阻塞 Redis。
     */
    public long deleteByPrefix(String prefix) {
        String pattern = prefix + "*";
        Set<String> keysToDelete = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                keysToDelete.add(cursor.next());
            }
        }
        if (keysToDelete.isEmpty()) {
            return 0;
        }
        redis.delete(keysToDelete);
        log.info("[Cache] evict by prefix: {} ({} keys)", prefix, keysToDelete.size());
        return keysToDelete.size();
    }

    // ==================== Key 构建 ====================

    public static final String PREFIX_FC            = "uw:fc:";
    public static final String PREFIX_RULE          = "uw:rule:";
    public static final String PREFIX_RULE_HISTORY   = "uw:rule:history:";
    public static final String PREFIX_SCRIPT        = "uw:script:";
    public static final String PREFIX_CDT           = "uw:cdt:";
    public static final String PREFIX_SC            = "uw:sc:";

    public static String fcKey(String featureCode)        { return PREFIX_FC + featureCode; }
    public static String fcAllKey()                        { return PREFIX_FC + "__ALL__"; }
    public static String ruleKey(String ruleCode)          { return PREFIX_RULE + ruleCode; }
    public static String ruleAllKey()                      { return PREFIX_RULE + "__ALL__"; }
    public static String scriptKey(String scriptId)        { return PREFIX_SCRIPT + scriptId; }
    public static String scriptAllKey()                    { return PREFIX_SCRIPT + "__ALL__"; }
    public static String cdtKey(String tableCode)          { return PREFIX_CDT + tableCode; }
    public static String cdtAllKey()                       { return PREFIX_CDT + "__ALL__"; }
    public static String scKey(String scorecardCode)       { return PREFIX_SC + scorecardCode; }
    public static String scAllKey()                        { return PREFIX_SC + "__ALL__"; }
    public static String ruleHistoryKey(String ruleCode)    { return PREFIX_RULE_HISTORY + ruleCode; }

}
