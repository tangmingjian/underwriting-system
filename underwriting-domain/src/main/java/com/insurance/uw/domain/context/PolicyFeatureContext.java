package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        this.applicantCtx = new ApplicantFeatureContext(policy.getApplicant());
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

}
