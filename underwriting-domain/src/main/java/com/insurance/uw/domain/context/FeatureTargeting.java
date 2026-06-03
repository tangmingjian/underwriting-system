package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.*;

/**
 * 特征-目标路由映射，封装从原始需求映射到依赖传播后目标映射的全部逻辑。
 * 从 OrderFeatureContext 中提取，包含输入映射、派生映射及其构建和查询方法。
 */
public class FeatureTargeting {

    // ---- Input maps (由调用方 RuleApplicationService 推导，extract 早期注入) ----

    private Map<String, Map<String, Set<String>>> policyInsuredFeatureMap;
    private Map<String, Map<String, Set<String>>> policyApplicantFeatureMap;

    // ---- Derived maps (含依赖传播，在 executeOrderLayer 中构建) ----

    private Map<String, Set<String>> featureInsuredTargetMap;
    private Map<String, Set<String>> featurePolicyTargetMap;
    private Map<String, Map<String, Set<String>>> featureInsuredPolicyMap;

    public FeatureTargeting() {}

    /**
     * 设置输入映射（在 extract 早期调用，可接受 null）。
     */
    public void setInputMaps(Map<String, Map<String, Set<String>>> policyInsuredFeatureMap,
                             Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        this.policyInsuredFeatureMap = policyInsuredFeatureMap;
        this.policyApplicantFeatureMap = policyApplicantFeatureMap;
    }

    /**
     * 构建依赖传播后的派生映射（在 executeOrderLayer 中调用一次）。
     */
    public void buildDerivedMaps(Map<String, FeatureConfig> configMap) {
        this.featureInsuredTargetMap = buildFeatureInsuredTargetMap(configMap);
        this.featurePolicyTargetMap = buildFeaturePolicyTargetMap(configMap);
        this.featureInsuredPolicyMap = buildFeatureInsuredPolicyMap(configMap);
    }

    // ---- Raw map accessors (供 PolicyFeatureContext 等内部使用) ----

    @JsonIgnore
    public Map<String, Map<String, Set<String>>> getRawInsuredMap() {
        return policyInsuredFeatureMap;
    }

    @JsonIgnore
    public Map<String, Map<String, Set<String>>> getRawApplicantMap() {
        return policyApplicantFeatureMap;
    }

    // ---- Public setters for derived maps (test use) ----

    public void setFeatureInsuredTargetMap(Map<String, Set<String>> featureInsuredTargetMap) {
        this.featureInsuredTargetMap = featureInsuredTargetMap;
    }

    public void setFeaturePolicyTargetMap(Map<String, Set<String>> featurePolicyTargetMap) {
        this.featurePolicyTargetMap = featurePolicyTargetMap;
    }

    public void setFeatureInsuredPolicyMap(Map<String, Map<String, Set<String>>> featureInsuredPolicyMap) {
        this.featureInsuredPolicyMap = featureInsuredPolicyMap;
    }

    // ==================== Query API ====================

    /**
     * 获取需要指定特征的被保人ID集合（优先派生映射，回退到输入映射）。
     * 返回 null 表示无需过滤（全部被保人）。
     */
    public Set<String> getInsuredIdsForFeature(String featureCode) {
        if (featureInsuredTargetMap != null) {
            Set<String> ids = featureInsuredTargetMap.get(featureCode);
            if (ids != null && !ids.isEmpty()) {
                return ids;
            }
        }
        if (policyInsuredFeatureMap != null) {
            Set<String> ids = new HashSet<>();
            for (Map<String, Set<String>> byInsured : policyInsuredFeatureMap.values()) {
                for (Map.Entry<String, Set<String>> e : byInsured.entrySet()) {
                    if (e.getValue().contains(featureCode)) {
                        ids.add(e.getKey());
                    }
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        return null;
    }

    /**
     * 获取需要指定特征的保单ID集合（优先派生映射，回退到输入映射）。
     * 返回 null 表示无需过滤（全部保单）。
     */
    public Set<String> getPolicyIdsForFeature(String featureCode) {
        if (featurePolicyTargetMap != null) {
            Set<String> ids = featurePolicyTargetMap.get(featureCode);
            if (ids != null && !ids.isEmpty()) {
                return ids;
            }
        }
        Set<String> ids = new HashSet<>();
        if (policyInsuredFeatureMap != null) {
            for (Map.Entry<String, Map<String, Set<String>>> entry : policyInsuredFeatureMap.entrySet()) {
                for (Set<String> fcs : entry.getValue().values()) {
                    if (fcs.contains(featureCode)) {
                        ids.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        if (policyApplicantFeatureMap != null) {
            for (Map.Entry<String, Map<String, Set<String>>> entry : policyApplicantFeatureMap.entrySet()) {
                for (Set<String> fcs : entry.getValue().values()) {
                    if (fcs.contains(featureCode)) {
                        ids.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        if (!ids.isEmpty()) {
            return ids;
        }
        return null;
    }

    /**
     * 获取指定特征+被保人允许的保单ID集合（供 storeResults 中 INSURED 级别按保单过滤）。
     * 返回 null 表示无过滤，返回空集合表示该被保人不需要此特征。
     */
    public Set<String> getPolicyIdsForInsuredFeature(String featureCode, String insuredId) {
        if (featureInsuredPolicyMap == null) {
            return null;
        }
        Map<String, Set<String>> insPolMap = featureInsuredPolicyMap.get(featureCode);
        if (insPolMap == null) {
            return null;
        }
        return insPolMap.get(insuredId);
    }

    /**
     * 检查指定保单是否需要指定特征（用于 findPolicyCtx 过滤）。
     * 返回 false 表示该保单不需要此特征。
     */
    public boolean isPolicyTargetedForFeature(String policyId, String featureCode) {
        if (featurePolicyTargetMap != null) {
            Set<String> targets = featurePolicyTargetMap.get(featureCode);
            if (targets != null && !targets.isEmpty() && !targets.contains(policyId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取指定保单+被保人需要的特征码集合（从输入映射精确匹配）。
     * 返回 null 表示无映射（不过滤）。
     */
    public Set<String> getNeededFeaturesForInsured(String policyId, String insuredId) {
        if (policyInsuredFeatureMap == null) {
            return null;
        }
        Map<String, Set<String>> byInsured = policyInsuredFeatureMap.get(policyId);
        if (byInsured == null) {
            return null;
        }
        return byInsured.get(insuredId);
    }

    /**
     * 获取指定保单+投保人需要的特征码集合（从输入映射精确匹配）。
     * 返回 null 表示无映射（不过滤）。
     */
    public Set<String> getNeededFeaturesForApplicant(String policyId, String applicantId) {
        if (policyApplicantFeatureMap == null) {
            return null;
        }
        Map<String, Set<String>> byApplicant = policyApplicantFeatureMap.get(policyId);
        if (byApplicant == null) {
            return null;
        }
        return byApplicant.get(applicantId);
    }

    /**
     * 获取指定保单下所有需要的特征码（从被保人+投保人映射收集）。
     */
    public Set<String> collectFeatureCodesForPolicy(String policyId) {
        Set<String> codes = new HashSet<>();
        if (policyInsuredFeatureMap != null) {
            Map<String, Set<String>> byInsured = policyInsuredFeatureMap.get(policyId);
            if (byInsured != null) {
                byInsured.values().forEach(codes::addAll);
            }
        }
        if (policyApplicantFeatureMap != null) {
            Map<String, Set<String>> byApplicant = policyApplicantFeatureMap.get(policyId);
            if (byApplicant != null) {
                byApplicant.values().forEach(codes::addAll);
            }
        }
        return codes;
    }

    /**
     * 收集所有输入映射中的特征码（跨全部保单）。
     */
    public Set<String> collectAllFeatureCodes() {
        Set<String> codes = new HashSet<>();
        if (policyInsuredFeatureMap != null) {
            for (Map<String, Set<String>> byInsured : policyInsuredFeatureMap.values()) {
                for (Set<String> features : byInsured.values()) {
                    codes.addAll(features);
                }
            }
        }
        if (policyApplicantFeatureMap != null) {
            for (Map<String, Set<String>> byApplicant : policyApplicantFeatureMap.values()) {
                for (Set<String> features : byApplicant.values()) {
                    codes.addAll(features);
                }
            }
        }
        return codes;
    }

    /**
     * 检查指定特征是否有任何实体目标需要。
     *
     * <p>用于 ORDER 层按需过滤：若特征不在任何保单的被保人/投保人映射中，
     * 则跳过计算以避免无效开销。</p>
     *
     * <p>检查路径：先查 getInsuredIdsForFeature（被保人），再查 getPolicyIdsForFeature（保单/投保人）。
     * 两者均返回 null 或空集合时返回 false。</p>
     *
     * @param featureCode 特征码
     * @return true 表示至少有一个实体需要该特征
     */
    public boolean isFeatureTargeted(String featureCode) {
        Set<String> insuredIds = getInsuredIdsForFeature(featureCode);
        if (insuredIds != null && !insuredIds.isEmpty()) {
            return true;
        }
        Set<String> policyIds = getPolicyIdsForFeature(featureCode);
        return policyIds != null && !policyIds.isEmpty();
    }

    // ==================== Build methods (moved from FeatureExtractionServiceImpl) ====================

    /**
     * 构建特征→被保人目标映射，包含依赖传播。
     */
    public Map<String, Set<String>> buildFeatureInsuredTargetMap(Map<String, FeatureConfig> configMap) {
        Map<String, Set<String>> targetMap = new LinkedHashMap<>();
        if (policyInsuredFeatureMap == null) {
            return targetMap;
        }
        for (Map<String, Set<String>> byInsured : policyInsuredFeatureMap.values()) {
            for (Map.Entry<String, Set<String>> e : byInsured.entrySet()) {
                String insuredId = e.getKey();
                for (String fc : e.getValue()) {
                    targetMap.computeIfAbsent(fc, k -> new LinkedHashSet<>()).add(insuredId);
                }
            }
        }
        propagateTargets(targetMap, configMap);
        return targetMap;
    }

    /**
     * 构建特征→保单目标映射，包含依赖传播。
     */
    private Map<String, Set<String>> buildFeaturePolicyTargetMap(Map<String, FeatureConfig> configMap) {
        Map<String, Set<String>> targetMap = new LinkedHashMap<>();
        if (policyInsuredFeatureMap != null) {
            for (Map.Entry<String, Map<String, Set<String>>> entry : policyInsuredFeatureMap.entrySet()) {
                String policyId = entry.getKey();
                for (Set<String> fcs : entry.getValue().values()) {
                    for (String fc : fcs) {
                        targetMap.computeIfAbsent(fc, k -> new LinkedHashSet<>()).add(policyId);
                    }
                }
            }
        }
        if (policyApplicantFeatureMap != null) {
            for (Map.Entry<String, Map<String, Set<String>>> entry : policyApplicantFeatureMap.entrySet()) {
                String policyId = entry.getKey();
                for (Set<String> fcs : entry.getValue().values()) {
                    for (String fc : fcs) {
                        targetMap.computeIfAbsent(fc, k -> new LinkedHashSet<>()).add(policyId);
                    }
                }
            }
        }
        propagateTargets(targetMap, configMap);
        return targetMap;
    }

    /**
     * 构建特征→被保人→保单目标映射，包含依赖传播（保留保单维度）。
     */
    private Map<String, Map<String, Set<String>>> buildFeatureInsuredPolicyMap(Map<String, FeatureConfig> configMap) {
        Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
        if (policyInsuredFeatureMap == null) {
            return result;
        }
        for (Map.Entry<String, Map<String, Set<String>>> polEntry : policyInsuredFeatureMap.entrySet()) {
            String policyId = polEntry.getKey();
            for (Map.Entry<String, Set<String>> insEntry : polEntry.getValue().entrySet()) {
                String insuredId = insEntry.getKey();
                for (String fc : insEntry.getValue()) {
                    result.computeIfAbsent(fc, k -> new LinkedHashMap<>())
                            .computeIfAbsent(insuredId, k2 -> new LinkedHashSet<>())
                            .add(policyId);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Map<String, Set<String>>> newEntries = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Set<String>>> fcEntry : result.entrySet()) {
                String fc = fcEntry.getKey();
                Map<String, Set<String>> insuredPolicyMap = fcEntry.getValue();
                FeatureConfig cfg = configMap.get(fc);
                if (cfg != null && cfg.getDependsOn() != null) {
                    for (String dep : cfg.getDependsOn()) {
                        Map<String, Set<String>> depMap = result.getOrDefault(dep, Map.of());
                        for (Map.Entry<String, Set<String>> insEntry : insuredPolicyMap.entrySet()) {
                            String insuredId = insEntry.getKey();
                            Set<String> policyIds = insEntry.getValue();
                            Set<String> existing = depMap.getOrDefault(insuredId, Set.of());
                            for (String polId : policyIds) {
                                if (!existing.contains(polId)) {
                                    newEntries.computeIfAbsent(dep, k -> new LinkedHashMap<>())
                                            .computeIfAbsent(insuredId, k2 -> new LinkedHashSet<>())
                                            .add(polId);
                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }
            newEntries.forEach((fc, insMap) -> {
                Map<String, Set<String>> target = result.computeIfAbsent(fc, k -> new LinkedHashMap<>());
                insMap.forEach((insId, polIds) ->
                        target.computeIfAbsent(insId, k -> new LinkedHashSet<>()).addAll(polIds));
            });
        }
        return result;
    }

    /**
     * 沿 dependsOn 反向传播目标：若特征 Y（目标集合 T）依赖 X，则 X 的目标 ∪= T。
     * 直接修改传入的 targetMap。
     */
    private void propagateTargets(Map<String, Set<String>> targetMap, Map<String, FeatureConfig> configMap) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Set<String>> newTargets = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : targetMap.entrySet()) {
                String fc = entry.getKey();
                Set<String> targets = entry.getValue();
                FeatureConfig cfg = configMap.get(fc);
                if (cfg != null && cfg.getDependsOn() != null) {
                    for (String dep : cfg.getDependsOn()) {
                        Set<String> existing = targetMap.getOrDefault(dep, Set.of());
                        for (String t : targets) {
                            if (!existing.contains(t)) {
                                newTargets.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(t);
                                changed = true;
                            }
                        }
                    }
                }
            }
            newTargets.forEach((k, v) -> targetMap.computeIfAbsent(k, key -> new LinkedHashSet<>()).addAll(v));
        }
    }
}
