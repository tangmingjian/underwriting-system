package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.uw.domain.model.entity.Order;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单特征上下文 — 顶层上下文，持有订单引用、所有保单上下文、订单级特征
 *
 * 零拷贝设计：只持有 Order 对象引用。
 * 构建时递归创建所有子级上下文树。
 */
public class OrderFeatureContext {
    @JsonIgnore
    private final Order order;
    private final List<PolicyFeatureContext> policyContexts;
    private final Map<String, Object> orderFeatures = new HashMap<>();

    /**
     * 由调度器在执行前注入（非持久化，仅当前次核保有效）
     * 保单 → 被保人 → 需要的特征码集合（按保单隔离）
     */
    @JsonIgnore
    private Map<String, Map<String, Set<String>>> policyInsuredFeatureMap;

    /**
     * 由调度器在执行前注入（非持久化，仅当前次核保有效）
     * 保单 → 投保人 → 需要的特征码集合（按保单隔离）
     */
    @JsonIgnore
    private Map<String, Map<String, Set<String>>> policyApplicantFeatureMap;

    /**
     * 由调度器在执行前注入，包含依赖传播后的目标映射（非持久化）。
     * 特征码 → 需要该特征的被保人ID集合。
     * 解决了依赖特征在 policyInsuredFeatureMap 中无记录导致回退到全量的问题。
     */
    @JsonIgnore
    private Map<String, Set<String>> featureInsuredTargetMap;

    /**
     * 由调度器在执行前注入，包含依赖传播后的目标映射（非持久化）。
     * 特征码 → 需要该特征的保单ID集合。
     */
    @JsonIgnore
    private Map<String, Set<String>> featurePolicyTargetMap;

    public OrderFeatureContext(Order order) {
        this.order = order;
        this.policyContexts = order.getPolicies().stream()
                .map(p -> new PolicyFeatureContext(p, this))
                .collect(Collectors.toList());
    }

    // ---- 原始对象引用（仅内部使用，不参与序列化） ----
    @JsonIgnore
    public Order getOrder() { return order; }

    // ---- 代理属性 ----
    public String getOrderId() { return order.getId(); }
    public String getChannel() { return order.getChannel(); }

    // ---- 子级上下文 ----
    public List<PolicyFeatureContext> getPolicies() { return policyContexts; }

    // ---- 特征结果 ----
    public Map<String, Object> getOrderFeatures() { return orderFeatures; }

    // ---- 特征→被保人/保单映射（由调度器注入） ----

    public void setPolicyInsuredFeatureMap(Map<String, Map<String, Set<String>>> policyInsuredFeatureMap) {
        this.policyInsuredFeatureMap = policyInsuredFeatureMap;
    }

    Map<String, Map<String, Set<String>>> getPolicyInsuredFeatureMap() {
        return policyInsuredFeatureMap;
    }

    public void setPolicyApplicantFeatureMap(Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        this.policyApplicantFeatureMap = policyApplicantFeatureMap;
    }

    public void setFeatureInsuredTargetMap(Map<String, Set<String>> featureInsuredTargetMap) {
        this.featureInsuredTargetMap = featureInsuredTargetMap;
    }

    public void setFeaturePolicyTargetMap(Map<String, Set<String>> featurePolicyTargetMap) {
        this.featurePolicyTargetMap = featurePolicyTargetMap;
    }

    // ---- 便捷查找 ----

    /**
     * 按被保人ID查找对应的被保人上下文
     */
    public InsuredFeatureContext findInsuredCtx(String insuredId) {
        return policyContexts.stream()
                .flatMap(pc -> pc.getInsureds().stream())
                .filter(ic -> ic.getInsuredId().equals(insuredId))
                .findFirst().orElse(null);
    }

    /**
     * 按保单ID查找保单上下文
     */
    public PolicyFeatureContext findPolicyCtx(String policyId) {
        return policyContexts.stream()
                .filter(pc -> pc.getPolicyId().equals(policyId))
                .findFirst().orElse(null);
    }

    /**
     * 获取所有被保人上下文（扁平化）
     */
    public List<InsuredFeatureContext> getAllInsuredContexts() {
        return policyContexts.stream()
                .flatMap(pc -> pc.getInsureds().stream())
                .collect(Collectors.toList());
    }

    /**
     * 按特征码返回相关被保人上下文（含 customerNos）。
     * 优先使用 featureInsuredTargetMap（含依赖传播），回退到 policyInsuredFeatureMap。
     * 若都无匹配 → 回退到 getAllInsuredContexts()。
     */
    public List<InsuredFeatureContext> getInsuredsForFeature(String featureCode) {
        Set<String> matchingIds = null;

        // 优先：使用依赖传播后的目标映射（解决依赖特征无记录的问题）
        if (featureInsuredTargetMap != null) {
            matchingIds = featureInsuredTargetMap.get(featureCode);
        }

        // 回退：从原始 policyInsuredFeatureMap 推导
        if (matchingIds == null && policyInsuredFeatureMap != null) {
            matchingIds = new HashSet<>();
            for (Map<String, Set<String>> byInsured : policyInsuredFeatureMap.values()) {
                for (Map.Entry<String, Set<String>> e : byInsured.entrySet()) {
                    if (e.getValue().contains(featureCode)) {
                        matchingIds.add(e.getKey());
                    }
                }
            }
        }

        Set<String> finalIds = matchingIds;
        if (finalIds == null || finalIds.isEmpty()) {
            return getAllInsuredContexts();
        }
        return policyContexts.stream()
                .flatMap(pc -> pc.getInsureds().stream())
                .filter(ic -> finalIds.contains(ic.getInsuredId()))
                .collect(Collectors.toList());
    }

    /**
     * 按特征码返回相关保单上下文（用于收集对应投保人）。
     * 优先使用 featurePolicyTargetMap（含依赖传播），回退到 policyInsuredFeatureMap + policyApplicantFeatureMap。
     * 若都无匹配 → 回退到 getPolicies()。
     */
    public List<PolicyFeatureContext> getPoliciesForFeature(String featureCode) {
        Set<String> matchingPolicyIds = null;

        // 优先：使用依赖传播后的目标映射
        if (featurePolicyTargetMap != null) {
            matchingPolicyIds = featurePolicyTargetMap.get(featureCode);
        }

        // 回退：从原始映射推导
        if (matchingPolicyIds == null) {
            matchingPolicyIds = new HashSet<>();
            if (policyInsuredFeatureMap != null) {
                for (Map.Entry<String, Map<String, Set<String>>> entry : policyInsuredFeatureMap.entrySet()) {
                    for (Set<String> fcs : entry.getValue().values()) {
                        if (fcs.contains(featureCode)) {
                            matchingPolicyIds.add(entry.getKey());
                            break;
                        }
                    }
                }
            }
            if (policyApplicantFeatureMap != null) {
                for (Map.Entry<String, Map<String, Set<String>>> entry : policyApplicantFeatureMap.entrySet()) {
                    for (Set<String> fcs : entry.getValue().values()) {
                        if (fcs.contains(featureCode)) {
                            matchingPolicyIds.add(entry.getKey());
                            break;
                        }
                    }
                }
            }
        }

        Set<String> finalPolicyIds = matchingPolicyIds;
        if (finalPolicyIds == null || finalPolicyIds.isEmpty()) {
            return getPolicies();
        }
        return policyContexts.stream()
                .filter(pc -> finalPolicyIds.contains(pc.getPolicyId()))
                .collect(Collectors.toList());
    }

}
