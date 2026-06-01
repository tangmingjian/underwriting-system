package com.insurance.uw.feature.api;

import java.util.HashMap;
import java.util.Map;

/**
 * 特征取数结果 — 完全可序列化，平铺结构，无循环引用
 * 替代 OrderFeatureContext 作为跨模块/跨进程的返回结果
 */
public class FeatureExtractionResult {

    /** ORDER 级特征 */
    private Map<String, Object> orderFeatures;

    /** 被保人维度 → 特征结果 (insuredId → {fc: val}) */
    private Map<String, Map<String, Object>> insuredFeatures;

    /** 投保人维度 → 特征结果 (applicantId → {fc: val}) */
    private Map<String, Map<String, Object>> applicantFeatures;

    /** 保单维度 → 特征结果 (policyId → {fc: val}) */
    private Map<String, Map<String, Object>> policyFeatures;

    public FeatureExtractionResult() {
        this.orderFeatures = new HashMap<>();
        this.insuredFeatures = new HashMap<>();
        this.applicantFeatures = new HashMap<>();
        this.policyFeatures = new HashMap<>();
    }

    public Map<String, Object> getOrderFeatures() { return orderFeatures; }
    public void setOrderFeatures(Map<String, Object> orderFeatures) { this.orderFeatures = orderFeatures; }

    public Map<String, Map<String, Object>> getInsuredFeatures() { return insuredFeatures; }
    public void setInsuredFeatures(Map<String, Map<String, Object>> insuredFeatures) {
        this.insuredFeatures = insuredFeatures;
    }

    public Map<String, Map<String, Object>> getApplicantFeatures() { return applicantFeatures; }
    public void setApplicantFeatures(Map<String, Map<String, Object>> applicantFeatures) {
        this.applicantFeatures = applicantFeatures;
    }

    public Map<String, Map<String, Object>> getPolicyFeatures() { return policyFeatures; }
    public void setPolicyFeatures(Map<String, Map<String, Object>> policyFeatures) {
        this.policyFeatures = policyFeatures;
    }
}
