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

    // Map<保单号,Map<被保人客户号,特征列表>>
    // policy→insured→features
    private Map<String, Map<String, Set<String>>> policyInsuredFeatureMap;
    // Map<保单号,Map<投保人客户号,特征列表>>
    // policy→applicant→features
    private Map<String, Map<String, Set<String>>> policyApplicantFeatureMap;

    // ---- Derived maps (含依赖传播，在 executeOrderLayer 中构建) ----

    private Map<String, Set<String>> featureInsuredTargetMap;
    private Map<String, Set<String>> featurePolicyTargetMap;
    //feature→insured→policies
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
     *
     * <h3>数据结构</h3>
     * <pre>
     * Map&lt;featureCode, Map&lt;insuredId, Set&lt;policyId&gt;&gt;&gt;
     * </pre>
     * 含义：对于某个特征码和某个被保人，该特征需要写入哪些保单。
     *
     * <h3>为什么需要保单维度</h3>
     * 同一个被保人可能出现在多张保单中，但不同保单需要不同的特征。
     * 例如 INS001 在 POL001 需要 [creditScore]，在 POL002 只需要 [age]。
     * 写入时必须精确到 (policyId, insuredId) 对，不能多写也不能漏写。
     *
     * <h3>构建过程（两阶段）</h3>
     * <ol>
     *   <li><b>输入反转</b>：将 policy→insured→features 反转为 feature→insured→policies</li>
     *   <li><b>依赖传播</b>：若特征 A 依赖 B，则 A 的所有 (insuredId, policyId) 目标
     *       也必须加入 B 的目标集合。由于依赖链可能多级（A→B→C），需要循环直到收敛。</li>
     * </ol>
     */
    private Map<String, Map<String, Set<String>>> buildFeatureInsuredPolicyMap(Map<String, FeatureConfig> configMap) {
        // 阶段1: 输入反转 policy→insured→features → feature→insured→policies
        Map<String, Map<String, Set<String>>> result = invertInputMap();
        if (result.isEmpty()) {
            return result;
        }
        // 阶段2: 沿 dependsOn 链传播目标（保留保单维度）
        propagateInsuredPolicyTargets(result, configMap);
        return result;
    }

    /**
     * 阶段1：将输入映射 policy→insured→features 反转为 feature→insured→policies。
     *
     * <p>输入 {@code policyInsuredFeatureMap}：
     * <pre>
     * POL001 → {INS001 → [creditScore], INS002 → [creditScore, age]}
     * </pre>
     * 输出：
     * <pre>
     * creditScore → {INS001 → [POL001], INS002 → [POL001]}
     * age         → {INS002 → [POL001]}
     * </pre>
     */
    private Map<String, Map<String, Set<String>>> invertInputMap() {
        Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
        if (policyInsuredFeatureMap == null) {
            return result;
        }
        for (var polEntry : policyInsuredFeatureMap.entrySet()) {
            String policyId = polEntry.getKey();
            for (var insEntry : polEntry.getValue().entrySet()) {
                String insuredId = insEntry.getKey();
                for (String fc : insEntry.getValue()) {
                    result.computeIfAbsent(fc, k -> new LinkedHashMap<>())
                            .computeIfAbsent(insuredId, k -> new LinkedHashSet<>())
                            .add(policyId);
                }
            }
        }
        return result;
    }

    /**
     * 阶段2：沿 dependsOn 链传播 (insuredId, policyId) 目标（保留保单维度）。
     *
     * <h3>传播规则</h3>
     * 若特征 A 依赖 B，且 (insuredId=I, policyId=P) 需要 A，则同样需要 B。
     *
     * <h3>为什么需要循环</h3>
     * 依赖链可能多级：A→B→C。假设 INS001@POL001 只需要 A：
     * <pre>
     * 初始:   A → {INS001: [POL001]}
     * 第1轮: A→B → B → {INS001: [POL001]}
     * 第2轮: B→C → C → {INS001: [POL001]}
     * 第3轮: 无新条目, 收敛退出
     * </pre>
     *
     * @param result  阶段1 的输出，直接在此 Map 上修改
     * @param configMap 特征配置，用于查找 dependsOn
     */
    private void propagateInsuredPolicyTargets(Map<String, Map<String, Set<String>>> result,
                                                Map<String, FeatureConfig> configMap) {
        boolean changed = true;
        while (changed) {
            changed = false;
            // 本轮新发现的 (fc → insuredId → [policyIds]) 条目
            Map<String, Map<String, Set<String>>> newEntries = new LinkedHashMap<>();

            for (var fcEntry : result.entrySet()) {
                String fc = fcEntry.getKey();
                Map<String, Set<String>> insuredPolicyMap = fcEntry.getValue();
                FeatureConfig cfg = configMap.get(fc);

                if (cfg == null || cfg.getDependsOn() == null) {
                    continue;
                }

                // fc 依赖的每个 dep，都需要继承 fc 的 (insuredId, policyId) 目标
                for (String dep : cfg.getDependsOn()) {
                    Map<String, Set<String>> depExisting = result.getOrDefault(dep, Map.of());
                    for (var insEntry : insuredPolicyMap.entrySet()) {
                        String insuredId = insEntry.getKey();
                        Set<String> neededPolicyIds = insEntry.getValue();
                        Set<String> alreadyHas = depExisting.getOrDefault(insuredId, Set.of());
                        for (String polId : neededPolicyIds) {
                            if (!alreadyHas.contains(polId)) {
                                newEntries.computeIfAbsent(dep, k -> new LinkedHashMap<>())
                                        .computeIfAbsent(insuredId, k2 -> new LinkedHashSet<>())
                                        .add(polId);
                                changed = true;
                            }
                        }
                    }
                }
            }

            // 将本轮新条目合并到 result，下一轮继续传播
            newEntries.forEach((fc, insMap) -> {
                Map<String, Set<String>> target = result.computeIfAbsent(fc, k -> new LinkedHashMap<>());
                insMap.forEach((insId, polIds) ->
                        target.computeIfAbsent(insId, k -> new LinkedHashSet<>()).addAll(polIds));
            });
        }
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
