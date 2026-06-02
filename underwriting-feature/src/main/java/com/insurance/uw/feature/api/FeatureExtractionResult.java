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

    /** 被保人维度 → 特征结果 (policyId → insuredId → {fc: val}) */
    private Map<String, Map<String, Map<String, Object>>> insuredFeatures;

    /** 投保人维度 → 特征结果 (policyId → applicantId → {fc: val}) */
    private Map<String, Map<String, Map<String, Object>>> applicantFeatures;

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

    public Map<String, Map<String, Map<String, Object>>> getInsuredFeatures() { return insuredFeatures; }
    public void setInsuredFeatures(Map<String, Map<String, Map<String, Object>>> insuredFeatures) {
        this.insuredFeatures = insuredFeatures;
    }

    public Map<String, Map<String, Map<String, Object>>> getApplicantFeatures() { return applicantFeatures; }
    public void setApplicantFeatures(Map<String, Map<String, Map<String, Object>>> applicantFeatures) {
        this.applicantFeatures = applicantFeatures;
    }

    public Map<String, Map<String, Object>> getPolicyFeatures() { return policyFeatures; }
    public void setPolicyFeatures(Map<String, Map<String, Object>> policyFeatures) {
        this.policyFeatures = policyFeatures;
    }

    // ==================== 便捷方法 ====================

    /**
     * 写入被保人特征（按保单隔离）
     */
    public void putInsuredFeature(String policyId, String insuredId, Map<String, Object> features) {
        insuredFeatures
                .computeIfAbsent(policyId, k -> new HashMap<>())
                .put(insuredId, features);
    }

    /**
     * 按保单+被保人查找特征
     */
    public Map<String, Object> getInsuredFeature(String policyId, String insuredId) {
        Map<String, Map<String, Object>> byPolicy = insuredFeatures.get(policyId);
        return byPolicy != null ? byPolicy.get(insuredId) : null;
    }

    /**
     * 写入投保人特征（按保单隔离）
     */
    public void putApplicantFeature(String policyId, String applicantId, Map<String, Object> features) {
        applicantFeatures
                .computeIfAbsent(policyId, k -> new HashMap<>())
                .put(applicantId, features);
    }

    /**
     * 按保单+投保人查找特征
     */
    public Map<String, Object> getApplicantFeature(String policyId, String applicantId) {
        Map<String, Map<String, Object>> byPolicy = applicantFeatures.get(policyId);
        return byPolicy != null ? byPolicy.get(applicantId) : null;
    }
}
