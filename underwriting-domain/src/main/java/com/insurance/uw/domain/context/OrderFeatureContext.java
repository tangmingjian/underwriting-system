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

    public void setPolicyApplicantFeatureMap(Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        this.policyApplicantFeatureMap = policyApplicantFeatureMap;
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
     * 从 policyInsuredFeatureMap 推导：扫描所有保单+被保人，过滤出包含该特征码的被保人。
     * 若 mapping 未注入或无匹配 → 回退到 getAllInsuredContexts()。
     */
    public List<InsuredFeatureContext> getInsuredsForFeature(String featureCode) {
        if (policyInsuredFeatureMap == null) {
            return getAllInsuredContexts();
        }
        Set<String> matchingIds = new HashSet<>();
        for (Map<String, Set<String>> byInsured : policyInsuredFeatureMap.values()) {
            for (Map.Entry<String, Set<String>> e : byInsured.entrySet()) {
                if (e.getValue().contains(featureCode)) {
                    matchingIds.add(e.getKey());
                }
            }
        }
        if (matchingIds.isEmpty()) {
            return getAllInsuredContexts();
        }
        return policyContexts.stream()
                .flatMap(pc -> pc.getInsureds().stream())
                .filter(ic -> matchingIds.contains(ic.getInsuredId()))
                .collect(Collectors.toList());
    }

    /**
     * 按特征码返回相关保单上下文（用于收集对应投保人）。
     * 从 policyInsuredFeatureMap + policyApplicantFeatureMap 推导：过滤出包含该特征码的保单。
     * 若 mapping 未注入或无匹配 → 回退到 getPolicies()。
     */
    public List<PolicyFeatureContext> getPoliciesForFeature(String featureCode) {
        Set<String> matchingPolicyIds = new HashSet<>();
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
        if (matchingPolicyIds.isEmpty()) {
            return getPolicies();
        }
        return policyContexts.stream()
                .filter(pc -> matchingPolicyIds.contains(pc.getPolicyId()))
                .collect(Collectors.toList());
    }

}
