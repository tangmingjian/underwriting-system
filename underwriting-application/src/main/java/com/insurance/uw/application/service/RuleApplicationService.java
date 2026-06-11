package com.insurance.uw.application.service;

import com.insurance.uw.engine.core.rule.WordingResolver;
import com.insurance.uw.engine.core.rule.engine.RuleEngineFactory;
import com.insurance.uw.common.enums.EvalType;
import com.insurance.uw.common.enums.RuleType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.model.entity.UnderwritingRuleHistory;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则应用服务 — 规则管理 + 特征请求构建 + SpEL 核保评估
 */
public class RuleApplicationService {

    private final UnderwritingRuleRepository repository;
    private final RuleEngineFactory ruleEngineFactory;
    private final WordingResolver wordingResolver;

    public RuleApplicationService(UnderwritingRuleRepository repository,
                                   RuleEngineFactory ruleEngineFactory,
                                   WordingResolver wordingResolver) {
        this.repository = repository;
        this.ruleEngineFactory = ruleEngineFactory;
        this.wordingResolver = wordingResolver;
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

    public List<UnderwritingRuleHistory> getHistory(String ruleCode) {
        return repository.findHistoryByRuleCode(ruleCode);
    }

    // ==================== 特征请求构建 ====================

    /**
     * 从规则推导特征码 + 实体映射，生成 FeatureExtractionRequest。
     * 原先 buildFeatureInsuredMapping 的逻辑迁移至此。
     */
    public FeatureExtractionRequest buildExtractionRequest(Order order) {
        List<UnderwritingRule> allRules = repository.findAllEnabled();

        Map<String, Map<String, Set<String>>> policyInsuredFeatureMap = new HashMap<>();
        Map<String, Map<String, Set<String>>> policyApplicantFeatureMap = new HashMap<>();

        for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
            String productCode = policy.getProduct() != null ? policy.getProduct().getProductCode() : null;
            Set<String> policyInsuredIds = policy.getInsureds().stream()
                    .map(com.insurance.uw.domain.model.entity.Insured::getId)
                    .collect(Collectors.toSet());
            String applicantId = policy.getApplicant() != null ? policy.getApplicant().getId() : null;

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
                        // 逐保单隔离：被保人特征
                        for (String insuredId : policyInsuredIds) {
                            policyInsuredFeatureMap
                                    .computeIfAbsent(policy.getId(), k -> new HashMap<>())
                                    .computeIfAbsent(insuredId, k -> new HashSet<>())
                                    .add(fc);
                        }
                        // 逐保单隔离：投保人特征
                        if (applicantId != null) {
                            policyApplicantFeatureMap
                                    .computeIfAbsent(policy.getId(), k -> new HashMap<>())
                                    .computeIfAbsent(applicantId, k -> new HashSet<>())
                                    .add(fc);
                        }
                    }
                }
            }
        }

        FeatureExtractionRequest request = new FeatureExtractionRequest();
        request.setOrder(order);
        request.setPolicyInsuredFeatureMap(policyInsuredFeatureMap);
        request.setPolicyApplicantFeatureMap(policyApplicantFeatureMap);
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
                        boolean passed = evaluateExpression(rule.getExpression(), features,
                                rule.getEvalType());
                        Map<String, String> wording = wordingResolver.resolve(
                                rule.getWordingConfig(), features, passed);
                        results.add(new UnderwritingResult(
                                "INSURED", insured.getId(), insured.getName(),
                                rule.getRuleCode(), rule.getRuleName(), passed, wording));
                    }
                }
                break;

            case APPLICANT:
                for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
                    if (policy.getApplicant() == null) continue;
                    String applicantId = policy.getApplicant().getId();
                    Map<String, Object> features = collectForApplicant(result, applicantId, policy.getId());
                    boolean passed = evaluateExpression(rule.getExpression(), features,
                            rule.getEvalType());
                    Map<String, String> wording = wordingResolver.resolve(
                            rule.getWordingConfig(), features, passed);
                    results.add(new UnderwritingResult(
                            "APPLICANT", applicantId, policy.getApplicant().getName(),
                            rule.getRuleCode(), rule.getRuleName(), passed, wording));
                }
                break;

            case POLICY:
                for (com.insurance.uw.domain.model.entity.Policy policy : order.getPolicies()) {
                    Map<String, Object> features = collectForPolicy(result, policy.getId());
                    boolean passed = evaluateExpression(rule.getExpression(), features,
                            rule.getEvalType());
                    Map<String, String> wording = wordingResolver.resolve(
                            rule.getWordingConfig(), features, passed);
                    results.add(new UnderwritingResult(
                            "POLICY", policy.getId(), null,
                            rule.getRuleCode(), rule.getRuleName(), passed, wording));
                }
                break;

            case ORDER:
                Map<String, Object> orderFeatures = collectForOrder(result);
                boolean orderPassed = evaluateExpression(rule.getExpression(), orderFeatures,
                        rule.getEvalType());
                Map<String, String> wording = wordingResolver.resolve(
                        rule.getWordingConfig(), orderFeatures, orderPassed);
                results.add(new UnderwritingResult(
                        "ORDER", order.getId(), null,
                        rule.getRuleCode(), rule.getRuleName(), orderPassed, wording));
                break;
        }

        return results;
    }

    private boolean evaluateExpression(String expression, Map<String, Object> features,
                                        EvalType evalType) {
        com.insurance.uw.engine.core.enums.EvalType engEvalType = evalType != null
                ? com.insurance.uw.engine.core.enums.EvalType.valueOf(evalType.name())
                : null;
        return ruleEngineFactory.evaluate(engEvalType, features, expression);
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
        if (applicantId != null && policyId != null) {
            Map<String, Object> appFeats = result.getApplicantFeature(policyId, applicantId);
            if (appFeats != null) all.putAll(appFeats);
        }
        Map<String, Object> insFeats = result.getInsuredFeature(policyId, insuredId);
        if (insFeats != null) all.putAll(insFeats);
        return all;
    }

    private Map<String, Object> collectForApplicant(FeatureExtractionResult result,
                                                     String applicantId, String policyId) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.putAll(result.getOrderFeatures());
        Map<String, Object> appFeats = result.getApplicantFeature(policyId, applicantId);
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
        private final Map<String, String> wordingBySide;

        public UnderwritingResult(String level, String targetId, String targetName,
                                  String ruleCode, String ruleName, boolean passed) {
            this(level, targetId, targetName, ruleCode, ruleName, passed, Collections.emptyMap());
        }

        public UnderwritingResult(String level, String targetId, String targetName,
                                  String ruleCode, String ruleName, boolean passed,
                                  Map<String, String> wordingBySide) {
            this.level = level;
            this.targetId = targetId;
            this.targetName = targetName;
            this.ruleCode = ruleCode;
            this.ruleName = ruleName;
            this.passed = passed;
            this.wordingBySide = Collections.unmodifiableMap(new LinkedHashMap<>(wordingBySide));
        }

        public String getLevel() { return level; }
        public String getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public String getRuleCode() { return ruleCode; }
        public String getRuleName() { return ruleName; }
        public boolean isPassed() { return passed; }
        public Map<String, String> getWordingBySide() { return wordingBySide; }
    }

}
