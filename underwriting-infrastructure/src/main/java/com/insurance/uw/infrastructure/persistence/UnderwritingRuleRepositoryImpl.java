package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.infrastructure.cache.CacheOps;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 核保规则仓储实现 — 带 Redis 缓存
 */
public class UnderwritingRuleRepositoryImpl implements UnderwritingRuleRepository {

    private final UnderwritingRuleMapper mapper;
    private final CacheOps cache;
    private final Duration ttl;

    public UnderwritingRuleRepositoryImpl(UnderwritingRuleMapper mapper, CacheOps cache,
                                           Duration ruleTtl) {
        this.mapper = mapper;
        this.cache = cache;
        this.ttl = ruleTtl;
    }

    @Override
    public Optional<UnderwritingRule> findByRuleCode(String ruleCode) {
        return cache.get(CacheOps.ruleKey(ruleCode), UnderwritingRule.class,
                () -> Optional.ofNullable(mapper.selectOne(
                        new LambdaQueryWrapper<UnderwritingRule>()
                                .eq(UnderwritingRule::getRuleCode, ruleCode))),
                ttl);
    }

    @Override
    public List<UnderwritingRule> findAllEnabled() {
        return cache.getList(CacheOps.ruleAllKey(), UnderwritingRule.class,
                () -> mapper.selectList(
                        new LambdaQueryWrapper<UnderwritingRule>()
                                .eq(UnderwritingRule::getStatus, 1)),
                ttl);
    }

    @Override
    public List<UnderwritingRule> findByRuleCodes(List<String> ruleCodes) {
        if (ruleCodes == null || ruleCodes.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(
                new LambdaQueryWrapper<UnderwritingRule>()
                        .in(UnderwritingRule::getRuleCode, ruleCodes)
                        .eq(UnderwritingRule::getStatus, 1));
    }

    @Override
    public void save(UnderwritingRule rule) {
        mapper.insert(rule);
        evict(rule);
    }

    @Override
    public void update(UnderwritingRule rule) {
        mapper.updateById(rule);
        evict(rule);
    }

    @Override
    public void delete(Long id) {
        UnderwritingRule rule = mapper.selectById(id);
        mapper.deleteById(id);
        if (rule != null) {
            evict(rule);
        }
    }

    private void evict(UnderwritingRule rule) {
        cache.evict(CacheOps.ruleKey(rule.getRuleCode()));
        cache.evict(CacheOps.ruleAllKey());
    }

}
