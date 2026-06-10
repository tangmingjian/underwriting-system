package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.common.enums.ChangeType;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.model.entity.UnderwritingRuleHistory;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.infrastructure.cache.CacheOps;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 核保规则仓储实现 — 带 Redis 缓存 + 版本历史归档
 */
public class UnderwritingRuleRepositoryImpl implements UnderwritingRuleRepository {

    private final UnderwritingRuleMapper mapper;
    private final UnderwritingRuleHistoryMapper historyMapper;
    private final CacheOps cache;
    private final Duration ttl;
    private final Duration historyTtl;

    public UnderwritingRuleRepositoryImpl(UnderwritingRuleMapper mapper,
                                           UnderwritingRuleHistoryMapper historyMapper,
                                           CacheOps cache,
                                           Duration ruleTtl,
                                           Duration historyTtl) {
        this.mapper = mapper;
        this.historyMapper = historyMapper;
        this.cache = cache;
        this.ttl = ruleTtl;
        this.historyTtl = historyTtl;
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
        rule.setVersion(1);
        mapper.insert(rule);
        insertHistory(rule, ChangeType.CREATE);
        evictRule(rule);
        evictHistory(rule);
    }

    @Override
    public void update(UnderwritingRule rule) {
        UnderwritingRule current = mapper.selectById(rule.getId());
        insertHistory(current, ChangeType.UPDATE);
        rule.setVersion(current.getVersion() + 1);
        mapper.updateById(rule);
        evictRule(rule);
        evictHistory(rule);
    }

    @Override
    public void delete(Long id) {
        UnderwritingRule rule = mapper.selectById(id);
        if (rule != null) {
            insertHistory(rule, ChangeType.DELETE);
        }
        mapper.deleteById(id);
        if (rule != null) {
            evictRule(rule);
            evictHistory(rule);
        }
    }

    @Override
    public List<UnderwritingRuleHistory> findHistoryByRuleCode(String ruleCode) {
        return cache.getList(CacheOps.ruleHistoryKey(ruleCode), UnderwritingRuleHistory.class,
                () -> historyMapper.selectList(
                        new LambdaQueryWrapper<UnderwritingRuleHistory>()
                                .eq(UnderwritingRuleHistory::getRuleCode, ruleCode)
                                .orderByDesc(UnderwritingRuleHistory::getVersion)),
                historyTtl);
    }

    // ==================== 私有方法 ====================

    private void insertHistory(UnderwritingRule rule, ChangeType changeType) {
        if (rule == null) return;
        historyMapper.insert(UnderwritingRuleHistory.from(rule, changeType));
    }

    private void evictRule(UnderwritingRule rule) {
        cache.evict(CacheOps.ruleKey(rule.getRuleCode()));
        cache.evict(CacheOps.ruleAllKey());
    }

    private void evictHistory(UnderwritingRule rule) {
        cache.evict(CacheOps.ruleHistoryKey(rule.getRuleCode()));
    }

}
