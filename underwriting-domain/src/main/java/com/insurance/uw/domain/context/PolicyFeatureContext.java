package com.insurance.uw.domain.context;

import com.insurance.uw.domain.model.entity.Policy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 投保单特征上下文 — 持有投保单引用、子级上下文、向上导航
 *
 * 零拷贝设计：只持有 Policy 对象引用。
 * 双向引用：持有 parentOrderCtx 向上导航，构建时将自身传入子级上下文。
 */
public class PolicyFeatureContext {

    private final Policy policy;
    private final OrderFeatureContext parentOrderCtx;
    private final ApplicantFeatureContext applicantCtx;
    private final List<InsuredFeatureContext> insuredContexts;
    private final Map<String, Object> policyFeatures = new HashMap<>();

    public PolicyFeatureContext(Policy policy, OrderFeatureContext parentOrderCtx) {
        this.policy = policy;
        this.parentOrderCtx = parentOrderCtx;
        this.applicantCtx = new ApplicantFeatureContext(policy.getApplicant());
        this.insuredContexts = policy.getInsureds().stream()
                .map(ins -> new InsuredFeatureContext(ins, this))
                .collect(Collectors.toList());
    }

    // ---- 原始对象引用 ----
    public Policy getPolicy() { return policy; }

    // ---- 代理属性 ----
    public String getPolicyId() { return policy.getId(); }
    public String getProductCode() { return policy.getProduct() != null ? policy.getProduct().getProductCode() : null; }

    // ---- 子级上下文 ----
    public ApplicantFeatureContext getApplicantCtx() { return applicantCtx; }
    public List<InsuredFeatureContext> getInsureds() { return insuredContexts; }

    // ---- 特征结果 ----
    public Map<String, Object> getPolicyFeatures() { return policyFeatures; }

    // ---- 向上导航 ----
    public OrderFeatureContext getOrderContext() { return parentOrderCtx; }

}
