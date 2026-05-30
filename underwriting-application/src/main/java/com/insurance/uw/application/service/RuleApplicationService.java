package com.insurance.uw.application.service;

import com.insurance.uw.common.enums.RuleType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.domain.service.FeatureCollector;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.*;

/**
 * 规则应用服务 — 规则管理 + SpEL 核保评估
 */
public class RuleApplicationService {

    private final UnderwritingRuleRepository repository;
    private final ExpressionParser parser = new SpelExpressionParser();

    public RuleApplicationService(UnderwritingRuleRepository repository) {
        this.repository = repository;
    }

    // ==================== 规则管理 ====================

    public List<UnderwritingRule> listAll() {
        return repository.findAllEnabled();
    }

    public Optional<UnderwritingRule> getByCode(String ruleCode) {
        return repository.findByRuleCode(ruleCode);
    }

    public void create(UnderwritingRule rule) {
        repository.save(rule);
    }

    public void update(UnderwritingRule rule) {
        repository.update(rule);
    }

    public void delete(Long id) {
        repository.delete(id);
    }

    // ==================== 核保评估 ====================

    /**
     * 对订单上下文执行核保评估
     *
     * @return 核保结果列表，每条记录包含评估对象、规则、是否通过
     */
    public List<UnderwritingResult> evaluate(OrderFeatureContext orderCtx) {
        List<UnderwritingRule> rules = repository.findAllEnabled();
        // 按优先级排序
        rules.sort(Comparator.comparingInt(r -> r.getPriority() != null ? r.getPriority() : 0));

        List<UnderwritingResult> results = new ArrayList<>();

        for (UnderwritingRule rule : rules) {
            results.addAll(evaluateRule(orderCtx, rule));
        }

        return results;
    }

    private List<UnderwritingResult> evaluateRule(OrderFeatureContext orderCtx, UnderwritingRule rule) {
        List<UnderwritingResult> results = new ArrayList<>();

        switch (rule.getRuleType()) {
            case INSURED:
                // 对每个投保单下的每个被保人评估
                for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
                    for (InsuredFeatureContext insCtx : polCtx.getInsureds()) {
                        Map<String, Object> features = FeatureCollector.collectForInsured(insCtx);
                        boolean passed = evaluateExpression(rule.getExpression(), features);
                        results.add(new UnderwritingResult(
                                "INSURED", insCtx.getInsuredId(), insCtx.getName(),
                                rule.getRuleCode(), rule.getRuleName(), passed));
                    }
                }
                break;

            case APPLICANT:
                for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
                    ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
                    Map<String, Object> features = FeatureCollector.collectForApplicant(polCtx);
                    boolean passed = evaluateExpression(rule.getExpression(), features);
                    results.add(new UnderwritingResult(
                            "APPLICANT", appCtx.getApplicantId(), appCtx.getApplicant().getName(),
                            rule.getRuleCode(), rule.getRuleName(), passed));
                }
                break;

            case POLICY:
                for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
                    Map<String, Object> features = FeatureCollector.collectForPolicy(polCtx);
                    boolean passed = evaluateExpression(rule.getExpression(), features);
                    results.add(new UnderwritingResult(
                            "POLICY", polCtx.getPolicyId(), null,
                            rule.getRuleCode(), rule.getRuleName(), passed));
                }
                break;
        }

        return results;
    }

    /**
     * 使用 SpEL 评估表达式
     * 表达式语法示例：#root['ins.healthScore'] > 60 && #root['ord.channelRisk'] < 80
     */
    private boolean evaluateExpression(String expression, Map<String, Object> features) {
        return Boolean.TRUE.equals(parser.parseExpression(expression).getValue(features, Boolean.class));
    }

    // ==================== 结果 DTO ====================

    public static class UnderwritingResult {
        private final String level;          // INSURED / APPLICANT / POLICY
        private final String targetId;        // 被保人ID / 投保人ID / 保单ID
        private final String targetName;      // 目标名称
        private final String ruleCode;        // 规则编码
        private final String ruleName;        // 规则名称
        private final boolean passed;         // 是否通过

        public UnderwritingResult(String level, String targetId, String targetName,
                                  String ruleCode, String ruleName, boolean passed) {
            this.level = level;
            this.targetId = targetId;
            this.targetName = targetName;
            this.ruleCode = ruleCode;
            this.ruleName = ruleName;
            this.passed = passed;
        }

        public String getLevel() { return level; }
        public String getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public String getRuleCode() { return ruleCode; }
        public String getRuleName() { return ruleName; }
        public boolean isPassed() { return passed; }

    }

}
