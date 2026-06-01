package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.infrastructure.cache.CacheOps;

import java.time.Duration;
import java.util.List;
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
        // 直接查库：按码批量查询与 findAllEnabled 数据差异大，不适合复用全量缓存
        return mapper.selectList(
                new LambdaQueryWrapper<FeatureConfig>()
                        .in(FeatureConfig::getFeatureCode, featureCodes)
                        .eq(FeatureConfig::getStatus, FeatureStatus.ACTIVE));
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

    private void evict(FeatureConfig config) {
        cache.evict(CacheOps.fcKey(config.getFeatureCode()));
        cache.evict(CacheOps.fcAllKey());
    }

}
