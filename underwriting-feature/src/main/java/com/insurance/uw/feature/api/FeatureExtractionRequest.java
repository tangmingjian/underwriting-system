package com.insurance.uw.feature.api;

import com.insurance.uw.domain.model.entity.Order;

import java.util.Map;
import java.util.Set;

/**
 * 特征取数请求 — 完全可序列化，不包含任何内部引用
 */
public class FeatureExtractionRequest {

    /** 订单（已可序列化） */
    private Order order;

    /** 需要计算的特征码 */
    private Set<String> featureCodes;

    /** 特征 → 被保人（按需限定范围） */
    private Map<String, Set<String>> featureToInsuredIds;

    /** 特征 → 保单（按需限定范围） */
    private Map<String, Set<String>> featureToPolicyIds;

    /** 被保人 → 同人客户号（for 同人查询） */
    private Map<String, Set<String>> customerNos;

    public FeatureExtractionRequest() {}

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public Set<String> getFeatureCodes() { return featureCodes; }
    public void setFeatureCodes(Set<String> featureCodes) { this.featureCodes = featureCodes; }

    public Map<String, Set<String>> getFeatureToInsuredIds() { return featureToInsuredIds; }
    public void setFeatureToInsuredIds(Map<String, Set<String>> featureToInsuredIds) {
        this.featureToInsuredIds = featureToInsuredIds;
    }

    public Map<String, Set<String>> getFeatureToPolicyIds() { return featureToPolicyIds; }
    public void setFeatureToPolicyIds(Map<String, Set<String>> featureToPolicyIds) {
        this.featureToPolicyIds = featureToPolicyIds;
    }

    public Map<String, Set<String>> getCustomerNos() { return customerNos; }
    public void setCustomerNos(Map<String, Set<String>> customerNos) {
        this.customerNos = customerNos;
    }
}
