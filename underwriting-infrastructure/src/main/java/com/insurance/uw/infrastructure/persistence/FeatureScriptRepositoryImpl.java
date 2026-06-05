package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.infrastructure.cache.CacheOps;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 特征脚本仓储实现 — 带 Redis 缓存
 */
public class FeatureScriptRepositoryImpl implements FeatureScriptRepository {

    private final FeatureScriptMapper mapper;
    private final CacheOps cache;
    private final Duration ttl;

    public FeatureScriptRepositoryImpl(FeatureScriptMapper mapper, CacheOps cache,
                                        Duration scriptTtl) {
        this.mapper = mapper;
        this.cache = cache;
        this.ttl = scriptTtl;
    }

    @Override
    public Optional<FeatureScript> findByScriptId(String scriptId) {
        return cache.get(CacheOps.scriptKey(scriptId), FeatureScript.class,
                () -> Optional.ofNullable(mapper.selectOne(
                        new LambdaQueryWrapper<FeatureScript>()
                                .eq(FeatureScript::getScriptId, scriptId)
                                .eq(FeatureScript::getStatus, FeatureStatus.ACTIVE)
                                .orderByDesc(FeatureScript::getVersion)
                                .last("LIMIT 1"))),
                ttl);
    }

    @Override
    public Optional<FeatureScript> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public List<FeatureScript> findAllEnabled() {
        return cache.getList(CacheOps.scriptAllKey(), FeatureScript.class,
                () -> mapper.selectList(
                        new LambdaQueryWrapper<FeatureScript>()
                                .eq(FeatureScript::getStatus, FeatureStatus.ACTIVE)),
                ttl);
    }

    @Override
    public void save(FeatureScript script) {
        mapper.insert(script);
        evict(script);
    }

    @Override
    public void update(FeatureScript script) {
        mapper.updateById(script);
        evict(script);
    }

    @Override
    public void delete(Long id) {
        FeatureScript script = mapper.selectById(id);
        mapper.deleteById(id);
        if (script != null) {
            evict(script);
        }
    }

    @Override
    public void evictCache(String scriptId) {
        cache.evict(CacheOps.scriptKey(scriptId));
        cache.evict(CacheOps.scriptAllKey());
    }

    private void evict(FeatureScript script) {
        cache.evict(CacheOps.scriptKey(script.getScriptId()));
        cache.evict(CacheOps.scriptAllKey());
    }

}
