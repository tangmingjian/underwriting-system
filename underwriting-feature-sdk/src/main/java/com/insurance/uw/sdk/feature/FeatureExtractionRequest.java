package com.insurance.uw.sdk.feature;

import com.insurance.uw.domain.model.entity.Order;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 特征取数请求 — 完全可序列化，不包含任何内部引用
 */
public class FeatureExtractionRequest {

    /** 订单（已可序列化） */
    private Order order;

    /** 保单 → 被保人 → 需要的特征码集合（按保单隔离） */
    private Map<String, Map<String, Set<String>>> policyInsuredFeatureMap;

    /** 保单 → 投保人 → 需要的特征码集合（按保单隔离） */
    private Map<String, Map<String, Set<String>>> policyApplicantFeatureMap;

    /** 被保人 → 同人客户号（for 同人查询） */
    private Map<String, Set<String>> customerNos;

    public FeatureExtractionRequest() {}

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public Set<String> getFeatureCodes() {
        Set<String> all = new LinkedHashSet<>();
        if (policyInsuredFeatureMap != null) {
            policyInsuredFeatureMap.values().forEach(m ->
                    m.values().forEach(all::addAll));
        }
        if (policyApplicantFeatureMap != null) {
            policyApplicantFeatureMap.values().forEach(m ->
                    m.values().forEach(all::addAll));
        }
        return all;
    }

    public Map<String, Map<String, Set<String>>> getPolicyInsuredFeatureMap() {
        return policyInsuredFeatureMap;
    }
    public void setPolicyInsuredFeatureMap(Map<String, Map<String, Set<String>>> policyInsuredFeatureMap) {
        this.policyInsuredFeatureMap = policyInsuredFeatureMap;
    }

    public Map<String, Map<String, Set<String>>> getPolicyApplicantFeatureMap() {
        return policyApplicantFeatureMap;
    }
    public void setPolicyApplicantFeatureMap(Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        this.policyApplicantFeatureMap = policyApplicantFeatureMap;
    }

    public Map<String, Set<String>> getCustomerNos() { return customerNos; }
    public void setCustomerNos(Map<String, Set<String>> customerNos) {
        this.customerNos = customerNos;
    }
}
