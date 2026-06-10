package com.insurance.uw.domain.repository;

import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.model.entity.UnderwritingRuleHistory;

import java.util.List;
import java.util.Optional;

/**
 * 核保规则仓储接口
 */
public interface UnderwritingRuleRepository {

    Optional<UnderwritingRule> findByRuleCode(String ruleCode);

    List<UnderwritingRule> findAllEnabled();

    List<UnderwritingRule> findByRuleCodes(List<String> ruleCodes);

    void save(UnderwritingRule rule);

    void update(UnderwritingRule rule);

    void delete(Long id);

    List<UnderwritingRuleHistory> findHistoryByRuleCode(String ruleCode);

}
