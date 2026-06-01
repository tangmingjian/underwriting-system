package com.insurance.uw.application.service;

import com.insurance.uw.common.enums.RuleType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.feature.api.FeatureExtractionRequest;
import com.insurance.uw.feature.api.FeatureExtractionResult;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则应用服务 — 规则管理 + 特征请求构建 + SpEL 核保评估
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

    // ==================== 特征请求构建 ====================

    /**
     * 从规则推导特征码 + 实体映射，生成 FeatureExtractionRequest。
     * 原先 buildFeatureInsuredMapping 的逻辑迁移至此。
     */
    public FeatureExtractionRequest buildExtractionRequest(Order order) {
        List<UnderwritingRule> allRules = repository.findAllEnabled();

        Set<String> featureCodes = new LinkedHashSet<>();
        Map<String, Set<String>> featureToInsuredIds = new HashMap<>();
        Map<String, Set<String>> featureToPolicyIds = new HashMap<>();

        for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
            String productCode = policy.getProduct() != null ? policy.getProduct().getProductCode() : null;
            Set<String> policyInsuredIds = policy.getInsureds().stream()
                    .map(com.insurance.uw.domain.model.entity.Insured::getId)
                    .collect(Collectors.toSet());

            for (UnderwritingRule rule : allRules) {
                if (rule.getProductCode() != null && !rule.getProductCode().equals(productCode)) {
                    continue;
                }
                String featureCodesStr = rule.getFeatureCodes();
                if (featureCodesStr == null || featureCodesStr.isBlank()) {
                    continue;
                }
                for (String fc : featureCodesStr.split(",")) {
                    fc = fc.trim();
                    if (!fc.isEmpty()) {
                        featureCodes.add(fc);
                        featureToInsuredIds.computeIfAbsent(fc, k -> new HashSet<>()).addAll(policyInsuredIds);
                        featureToPolicyIds.computeIfAbsent(fc, k -> new HashSet<>()).add(policy.getId());
                    }
                }
            }
        }

        FeatureExtractionRequest request = new FeatureExtractionRequest();
        request.setOrder(order);
        request.setFeatureCodes(featureCodes);
        request.setFeatureToInsuredIds(featureToInsuredIds);
        request.setFeatureToPolicyIds(featureToPolicyIds);
        return request;
    }

    // ==================== 核保评估 ====================

    /**
     * 对订单执行核保评估（基于 FeatureExtractionResult 扁平结果）
     */
    public List<UnderwritingResult> evaluate(Order order, FeatureExtractionResult result) {
        List<UnderwritingRule> rules = new ArrayList<>(repository.findAllEnabled());
        rules.sort(Comparator.comparingInt(r -> r.getPriority() != null ? r.getPriority() : 0));

        List<UnderwritingResult> results = new ArrayList<>();

        for (UnderwritingRule rule : rules) {
            results.addAll(evaluateRule(order, result, rule));
        }

        return results;
    }

    private List<UnderwritingResult> evaluateRule(Order order, FeatureExtractionResult result,
                                                   UnderwritingRule rule) {
        List<UnderwritingResult> results = new ArrayList<>();

        switch (rule.getRuleType()) {
            case INSURED:
                for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
                    String applicantId = policy.getApplicant() != null ? policy.getApplicant().getId() : null;
                    for (com.insurance.uw.domain.model.entity.Insured insured : policy.getInsureds()) {
                        Map<String, Object> features = collectForInsured(result,
                                insured.getId(), policy.getId(), applicantId);
                        boolean passed = evaluateExpression(rule.getExpression(), features);
                        results.add(new UnderwritingResult(
                                "INSURED", insured.getId(), insured.getName(),
                                rule.getRuleCode(), rule.getRuleName(), passed));
                    }
                }
                break;

            case APPLICANT:
                for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
                    if (policy.getApplicant() == null) continue;
                    String applicantId = policy.getApplicant().getId();
                    Map<String, Object> features = collectForApplicant(result, applicantId);
                    boolean passed = evaluateExpression(rule.getExpression(), features);
                    results.add(new UnderwritingResult(
                            "APPLICANT", applicantId, policy.getApplicant().getName(),
                            rule.getRuleCode(), rule.getRuleName(), passed));
                }
                break;

            case POLICY:
                for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
                    Map<String, Object> features = collectForPolicy(result, policy.getId());
                    boolean passed = evaluateExpression(rule.getExpression(), features);
                    results.add(new UnderwritingResult(
                            "POLICY", policy.getId(), null,
                            rule.getRuleCode(), rule.getRuleName(), passed));
                }
                break;

            case ORDER:
                Map<String, Object> orderFeatures = collectForOrder(result);
                boolean orderPassed = evaluateExpression(rule.getExpression(), orderFeatures);
                results.add(new UnderwritingResult(
                        "ORDER", order.getId(), null,
                        rule.getRuleCode(), rule.getRuleName(), orderPassed));
                break;
        }

        return results;
    }

    private boolean evaluateExpression(String expression, Map<String, Object> features) {
        return Boolean.TRUE.equals(parser.parseExpression(expression).getValue(features, Boolean.class));
    }

    // ==================== 特征收集（从 FeatureExtractionResult 扁平 map） ====================

    private Map<String, Object> collectForInsured(FeatureExtractionResult result,
                                                   String insuredId, String policyId, String applicantId) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.putAll(result.getOrderFeatures());
        if (policyId != null) {
            Map<String, Object> polFeats = result.getPolicyFeatures().get(policyId);
            if (polFeats != null) all.putAll(polFeats);
        }
        if (applicantId != null) {
            Map<String, Object> appFeats = result.getApplicantFeatures().get(applicantId);
            if (appFeats != null) all.putAll(appFeats);
        }
        Map<String, Object> insFeats = result.getInsuredFeatures().get(insuredId);
        if (insFeats != null) all.putAll(insFeats);
        return all;
    }

    private Map<String, Object> collectForApplicant(FeatureExtractionResult result, String applicantId) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.putAll(result.getOrderFeatures());
        Map<String, Object> appFeats = result.getApplicantFeatures().get(applicantId);
        if (appFeats != null) all.putAll(appFeats);
        return all;
    }

    private Map<String, Object> collectForPolicy(FeatureExtractionResult result, String policyId) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.putAll(result.getOrderFeatures());
        Map<String, Object> polFeats = result.getPolicyFeatures().get(policyId);
        if (polFeats != null) all.putAll(polFeats);
        return all;
    }

    private Map<String, Object> collectForOrder(FeatureExtractionResult result) {
        return new LinkedHashMap<>(result.getOrderFeatures());
    }

    // ==================== 结果 DTO ====================

    public static class UnderwritingResult {
        private final String level;
        private final String targetId;
        private final String targetName;
        private final String ruleCode;
        private final String ruleName;
        private final boolean passed;

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
