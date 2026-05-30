package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;

import java.util.List;
import java.util.Optional;

/**
 * 核保规则仓储实现
 */
public class UnderwritingRuleRepositoryImpl implements UnderwritingRuleRepository {

    private final UnderwritingRuleMapper mapper;

    public UnderwritingRuleRepositoryImpl(UnderwritingRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UnderwritingRule> findByRuleCode(String ruleCode) {
        UnderwritingRule rule = mapper.selectOne(
                new LambdaQueryWrapper<UnderwritingRule>()
                        .eq(UnderwritingRule::getRuleCode, ruleCode));
        return Optional.ofNullable(rule);
    }

    @Override
    public List<UnderwritingRule> findAllEnabled() {
        return mapper.selectList(
                new LambdaQueryWrapper<UnderwritingRule>()
                        .eq(UnderwritingRule::getStatus, 1));
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
    }

    @Override
    public void update(UnderwritingRule rule) {
        mapper.updateById(rule);
    }

    @Override
    public void delete(Long id) {
        mapper.deleteById(id);
    }

}
