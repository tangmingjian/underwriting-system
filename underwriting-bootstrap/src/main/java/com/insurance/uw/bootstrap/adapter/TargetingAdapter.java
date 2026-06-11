package com.insurance.uw.bootstrap.adapter;

import com.insurance.uw.sdk.feature.FeatureExtractionRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 领域 FeatureTargeting → 引擎 FeatureTargeting 转换适配器。
 *
 * <p>领域版用 typed key（policyId / insuredId / applicantId），
 * 引擎版用 path-keyed 格式（"POLICY:id" / "INSURED:id" / "APPLICANT:id"）。</p>
 */
public class TargetingAdapter {

    private TargetingAdapter() {}

    /**
     * 将请求中的领域 targeting 信息转换为引擎版 FeatureTargeting。
     */
    public static com.insurance.uw.engine.core.targeting.FeatureTargeting toEngineTargeting(
            FeatureExtractionRequest request) {
        com.insurance.uw.engine.core.targeting.FeatureTargeting engFt =
                new com.insurance.uw.engine.core.targeting.FeatureTargeting();

        Map<String, Map<String, Set<String>>> inputMap = new LinkedHashMap<>();

        // 被保人映射：policyId → {INSURED:insuredId → features}
        Map<String, Map<String, Set<String>>> insuredMap = request.getPolicyInsuredFeatureMap();
        if (insuredMap != null) {
            for (var policyEntry : insuredMap.entrySet()) {
                String policyId = policyEntry.getKey();
                String parentKey = com.insurance.uw.engine.core.targeting.FeatureTargeting.pathKey("POLICY", policyId);
                for (var insEntry : policyEntry.getValue().entrySet()) {
                    String insuredId = insEntry.getKey();
                    String childKey = com.insurance.uw.engine.core.targeting.FeatureTargeting.pathKey("INSURED", insuredId);
                    inputMap.computeIfAbsent(parentKey, k -> new LinkedHashMap<>())
                            .put(childKey, insEntry.getValue());
                }
            }
        }

        // 投保人映射：policyId → {APPLICANT:applicantId → features}
        Map<String, Map<String, Set<String>>> applicantMap = request.getPolicyApplicantFeatureMap();
        if (applicantMap != null) {
            for (var policyEntry : applicantMap.entrySet()) {
                String policyId = policyEntry.getKey();
                String parentKey = com.insurance.uw.engine.core.targeting.FeatureTargeting.pathKey("POLICY", policyId);
                for (var appEntry : policyEntry.getValue().entrySet()) {
                    String applicantId = appEntry.getKey();
                    String childKey = com.insurance.uw.engine.core.targeting.FeatureTargeting.pathKey("APPLICANT", applicantId);
                    inputMap.computeIfAbsent(parentKey, k -> new LinkedHashMap<>())
                            .put(childKey, appEntry.getValue());
                }
            }
        }

        engFt.setInputMap(inputMap);
        return engFt;
    }
}
