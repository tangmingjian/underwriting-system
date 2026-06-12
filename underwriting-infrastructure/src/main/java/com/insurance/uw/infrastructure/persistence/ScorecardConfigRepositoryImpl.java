package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.engine.core.cache.CacheOps;
import com.insurance.uw.engine.core.repository.ScorecardConfigRepository;
import com.insurance.uw.engine.core.model.entity.ScorecardConfig;


import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 评分卡配置仓储实现 — 带 Redis 缓存
 */
public class ScorecardConfigRepositoryImpl implements ScorecardConfigRepository {

    private final ScorecardConfigMapper mapper;
    private final CacheOps cache;
    private final Duration ttl;

    public ScorecardConfigRepositoryImpl(ScorecardConfigMapper mapper, CacheOps cache,
                                          Duration ttl) {
        this.mapper = mapper;
        this.cache = cache;
        this.ttl = ttl;
    }

    @Override
    public Optional<ScorecardConfig> findByScorecardCode(String scorecardCode) {
        return cache.get(CacheOps.scKey(scorecardCode), ScorecardConfig.class,
                () -> Optional.ofNullable(mapper.selectOne(
                        new LambdaQueryWrapper<ScorecardConfig>()
                                .eq(ScorecardConfig::getScorecardCode, scorecardCode))),
                ttl);
    }

    @Override
    public List<ScorecardConfig> findAllEnabled() {
        return cache.getList(CacheOps.scAllKey(), ScorecardConfig.class,
                () -> mapper.selectList(
                        new LambdaQueryWrapper<ScorecardConfig>()
                                .eq(ScorecardConfig::getStatus, 1)),
                ttl);
    }

    @Override
    public void save(ScorecardConfig config) {
        mapper.insert(config);
        evict(config);
    }

    @Override
    public void update(ScorecardConfig config) {
        mapper.updateById(config);
        evict(config);
    }

    @Override
    public void delete(Long id) {
        ScorecardConfig config = mapper.selectById(id);
        mapper.deleteById(id);
        if (config != null) {
            evict(config);
        }
    }

    private void evict(ScorecardConfig config) {
        cache.evict(CacheOps.scKey(config.getScorecardCode()));
        cache.evict(CacheOps.scAllKey());
    }
}
