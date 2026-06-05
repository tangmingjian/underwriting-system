package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.infrastructure.cache.CacheOps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 特征配置仓储实现 — 带 Redis 缓存
 */
public class FeatureConfigRepositoryImpl implements FeatureConfigRepository {

    private final FeatureConfigMapper mapper;
    private final CacheOps cache;
    private final Duration ttl;

    public FeatureConfigRepositoryImpl(FeatureConfigMapper mapper, CacheOps cache,
                                        Duration featureConfigTtl) {
        this.mapper = mapper;
        this.cache = cache;
        this.ttl = featureConfigTtl;
    }

    @Override
    public Optional<FeatureConfig> findByFeatureCode(String featureCode) {
        return cache.get(CacheOps.fcKey(featureCode), FeatureConfig.class,
                () -> Optional.ofNullable(mapper.selectOne(
                        new LambdaQueryWrapper<FeatureConfig>()
                                .eq(FeatureConfig::getFeatureCode, featureCode))),
                ttl);
    }

    @Override
    public List<FeatureConfig> findAllEnabled() {
        return cache.getList(CacheOps.fcAllKey(), FeatureConfig.class,
                () -> mapper.selectList(
                        new LambdaQueryWrapper<FeatureConfig>()
                                .eq(FeatureConfig::getStatus, FeatureStatus.ACTIVE)),
                ttl);
    }

    @Override
    public List<FeatureConfig> findByFeatureCodes(List<String> featureCodes) {
        if (featureCodes == null || featureCodes.isEmpty()) {
            return List.of();
        }
        // 1. Redis MGET 批量获取
        List<String> keys = featureCodes.stream().map(CacheOps::fcKey).toList();
        List<Optional<FeatureConfig>> cached = cache.multiGet(keys, FeatureConfig.class);

        // 2. 分离命中 / 未命中
        List<String> missingCodes = new ArrayList<>();
        List<FeatureConfig> results = new ArrayList<>();
        for (int i = 0; i < featureCodes.size(); i++) {
            Optional<FeatureConfig> opt = cached.get(i);
            if (opt.isPresent()) {
                results.add(opt.get());
            } else {
                missingCodes.add(featureCodes.get(i));
            }
        }

        // 3. 未命中查 DB 并回填
        if (!missingCodes.isEmpty()) {
            List<FeatureConfig> dbResults = mapper.selectList(
                    new LambdaQueryWrapper<FeatureConfig>()
                            .in(FeatureConfig::getFeatureCode, missingCodes)
                            .eq(FeatureConfig::getStatus, FeatureStatus.ACTIVE));
            Map<String, FeatureConfig> toCache = new LinkedHashMap<>();
            for (FeatureConfig fc : dbResults) {
                toCache.put(CacheOps.fcKey(fc.getFeatureCode()), fc);
            }
            cache.multiSet(toCache, ttl);
            results.addAll(dbResults);
        }

        return results;
    }

    @Override
    public Optional<FeatureConfig> findByFeatureCodeDirect(String featureCode) {
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<FeatureConfig>()
                        .eq(FeatureConfig::getFeatureCode, featureCode)));
    }

    @Override
    public void save(FeatureConfig config) {
        mapper.insert(config);
        evict(config);
    }

    @Override
    public void update(FeatureConfig config) {
        mapper.updateById(config);
        evict(config);
    }

    @Override
    public void delete(Long id) {
        FeatureConfig config = mapper.selectById(id);
        mapper.deleteById(id);
        if (config != null) {
            evict(config);
        }
    }

    @Override
    public void evictCache(String featureCode) {
        cache.evict(CacheOps.fcKey(featureCode));
        cache.evict(CacheOps.fcAllKey());
    }

    private void evict(FeatureConfig config) {
        cache.evict(CacheOps.fcKey(config.getFeatureCode()));
        cache.evict(CacheOps.fcAllKey());
    }

}
