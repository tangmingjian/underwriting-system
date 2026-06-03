package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.uw.domain.model.entity.Policy;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 投保单特征上下文 — 持有投保单引用、子级上下文、向上导航
 *
 * 零拷贝设计：只持有 Policy 对象引用。
 * 双向引用：持有 parentOrderCtx 向上导航，构建时将自身传入子级上下文。
 */
public class PolicyFeatureContext {
    @JsonIgnore
    private final Policy policy;
    @JsonIgnore
    private final OrderFeatureContext parentOrderCtx;
    private final ApplicantFeatureContext applicantCtx;
    private final List<InsuredFeatureContext> insuredContexts;
    private final Map<String, Object> policyFeatures = new HashMap<>();

    public PolicyFeatureContext(Policy policy, OrderFeatureContext parentOrderCtx) {
        this.policy = policy;
        this.parentOrderCtx = parentOrderCtx;
        this.applicantCtx = new ApplicantFeatureContext(policy.getApplicant(), this);
        this.insuredContexts = policy.getInsureds().stream()
                .map(ins -> new InsuredFeatureContext(ins, this))
                .collect(Collectors.toList());
    }

    // ---- 原始对象引用（仅内部使用，不参与序列化） ----
    @JsonIgnore
    public Policy getPolicy() { return policy; }

    // ---- 代理属性 ----
    public String getPolicyId() { return policy.getId(); }
    public String getProductCode() { return policy.getProduct() != null ? policy.getProduct().getProductCode() : null; }

    // ---- 子级上下文 ----
    public ApplicantFeatureContext getApplicantCtx() { return applicantCtx; }
    public List<InsuredFeatureContext> getInsureds() { return insuredContexts; }

    // ---- 特征结果 ----
    public Map<String, Object> getPolicyFeatures() { return policyFeatures; }

    // ---- 向上导航（仅内部导航，不参与序列化） ----
    @JsonIgnore
    public OrderFeatureContext getOrderContext() { return parentOrderCtx; }

    // ---- 按特征过滤被保人 ----

    /**
     * 按特征码返回当前保单下需要该特征的被保人上下文。
     * 从父级 OrderFeatureContext 的 policyInsuredFeatureMap 中读取当前保单的映射，
     * 精确匹配被保人-特征关系。若 featureCode 不在当前保单的映射中（如依赖特征），
     * 回退到父级的全局匹配（含 featureInsuredTargetMap 传播），过滤到当前保单实例。
     * 若都无匹配 → 回退到当前保单全部被保人。
     */
    public List<InsuredFeatureContext> getInsuredsForFeature(String featureCode) {
        if (parentOrderCtx == null) {
            return getInsureds();
        }

        // 优先：从 policyInsuredFeatureMap 读取当前保单的精确映射
        Map<String, Map<String, Set<String>>> policyInsuredMap = parentOrderCtx.getPolicyInsuredFeatureMap();
        if (policyInsuredMap != null) {
            Map<String, Set<String>> byInsured = policyInsuredMap.get(getPolicyId());
            if (byInsured != null) {
                List<InsuredFeatureContext> result = new ArrayList<>();
                for (InsuredFeatureContext ic : insuredContexts) {
                    Set<String> needed = byInsured.get(ic.getInsuredId());
                    if (needed != null && needed.contains(featureCode)) {
                        result.add(ic);
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        // 回退：使用父级的全局匹配（含 featureInsuredTargetMap），过滤到当前保单实例
        List<InsuredFeatureContext> allMatching = parentOrderCtx.getInsuredsForFeature(featureCode);
        List<InsuredFeatureContext> filtered = new ArrayList<>();
        for (InsuredFeatureContext ic : allMatching) {
            if (ic.getPolicyContext() == this) {
                filtered.add(ic);
            }
        }
        if (filtered.isEmpty()) {
            return getInsureds();
        }
        return filtered;
    }

}
