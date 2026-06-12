package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.engine.core.cache.CacheOps;
import com.insurance.uw.engine.core.model.entity.CrossDecisionTable;
import com.insurance.uw.engine.core.repository.CrossDecisionTableRepository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 交叉决策表仓储实现 — 带 Redis 缓存
 */
public class CrossDecisionTableRepositoryImpl implements CrossDecisionTableRepository {

    private final CrossDecisionTableMapper mapper;
    private final CacheOps cache;
    private final Duration ttl;

    public CrossDecisionTableRepositoryImpl(CrossDecisionTableMapper mapper, CacheOps cache,
                                             Duration ttl) {
        this.mapper = mapper;
        this.cache = cache;
        this.ttl = ttl;
    }

    @Override
    public Optional<CrossDecisionTable> findByTableCode(String tableCode) {
        return cache.get(CacheOps.cdtKey(tableCode), CrossDecisionTable.class,
                () -> Optional.ofNullable(mapper.selectOne(
                        new LambdaQueryWrapper<CrossDecisionTable>()
                                .eq(CrossDecisionTable::getTableCode, tableCode))),
                ttl);
    }

    @Override
    public List<CrossDecisionTable> findAllEnabled() {
        return cache.getList(CacheOps.cdtAllKey(), CrossDecisionTable.class,
                () -> mapper.selectList(
                        new LambdaQueryWrapper<CrossDecisionTable>()
                                .eq(CrossDecisionTable::getStatus, 1)),
                ttl);
    }

    @Override
    public void save(CrossDecisionTable table) {
        mapper.insert(table);
        evict(table);
    }

    @Override
    public void update(CrossDecisionTable table) {
        mapper.updateById(table);
        evict(table);
    }

    @Override
    public void delete(Long id) {
        CrossDecisionTable table = mapper.selectById(id);
        mapper.deleteById(id);
        if (table != null) {
            evict(table);
        }
    }

    private void evict(CrossDecisionTable table) {
        cache.evict(CacheOps.cdtKey(table.getTableCode()));
        cache.evict(CacheOps.cdtAllKey());
    }
}
