package com.insurance.uw.engine.core.targeting;

import com.insurance.uw.engine.core.model.entity.FeatureConfig;

import java.util.*;

/**
 * 特征-目标路由映射（泛化为 path-keyed）。
 *
 * <h3>与旧版的核心区别</h3>
 * 旧版用业务命名 key（policyId / insuredId / applicantId），
 * 新版泛化为 levelPath:entityId 的通用 key 格式，适配任意 ContextNode 层级结构。
 *
 * <h3>数据结构</h3>
 * <ul>
 *   <li>输入：Map&lt;parentLevelPath:parentEntityId, Map&lt;childLevelPath:childEntityId, Set&lt;featureCode&gt;&gt;&gt;</li>
 *   <li>派生：Map&lt;featureCode, Set&lt;levelPath:entityId&gt;&gt; 等</li>
 * </ul>
 */
public class FeatureTargeting {

    // ---- Input maps ----
    // Map<pathPrefix:parentId, Map<pathPrefix:childId, Set<featureCode>>>
    private Map<String, Map<String, Set<String>>> inputMap;

    // ---- Derived maps (含依赖传播) ----
    private Map<String, Set<String>> featureTargetMap;
    // feature → childId → Set<parentId>
    private Map<String, Map<String, Set<String>>> featureChildParentMap;

    public FeatureTargeting() {}

    /**
     * 设置输入映射。
     * inputMap: Map<parentPathKey, Map<childPathKey, Set<featureCode>>>
     * 其中 pathKey 格式为 "levelName:entityId"（如 "POLICY:P001"、"INSURED:I001"）。
     */
    public void setInputMap(Map<String, Map<String, Set<String>>> inputMap) {
        this.inputMap = inputMap;
    }

    /**
     * 构建依赖传播后的派生映射。
     */
    public void buildDerivedMaps(Map<String, FeatureConfig> configMap) {
        this.featureTargetMap = buildFeatureTargetMap(configMap);
        this.featureChildParentMap = buildFeatureChildParentMap(configMap);
    }

    // ---- Raw map accessors ----

    public Map<String, Map<String, Set<String>>> getRawInputMap() {
        return inputMap;
    }

    // ---- Public setters for derived maps (test use) ----

    public void setFeatureTargetMap(Map<String, Set<String>> featureTargetMap) {
        this.featureTargetMap = featureTargetMap;
    }

    public void setFeatureChildParentMap(Map<String, Map<String, Set<String>>> featureChildParentMap) {
        this.featureChildParentMap = featureChildParentMap;
    }

    // ==================== Query API ====================

    /**
     * 获取需要指定特征的目标 ID 集合（path-keyed）。
     * 返回 null 表示无需过滤。
     */
    public Set<String> getTargetIdsForFeature(String featureCode) {
        if (featureTargetMap != null) {
            Set<String> ids = featureTargetMap.get(featureCode);
            if (ids != null && !ids.isEmpty()) {
                return ids;
            }
        }
        if (inputMap != null) {
            Set<String> ids = new HashSet<>();
            for (Map<String, Set<String>> byChild : inputMap.values()) {
                for (Map.Entry<String, Set<String>> e : byChild.entrySet()) {
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
     * 获取指定特征+子节点允许的父节点 ID 集合。
     * 返回 null 表示无过滤。
     */
    public Set<String> getParentIdsForChildFeature(String featureCode, String childPathKey) {
        if (featureChildParentMap == null) {
            return null;
        }
        Map<String, Set<String>> childParentMap = featureChildParentMap.get(featureCode);
        if (childParentMap == null) {
            return null;
        }
        return childParentMap.get(childPathKey);
    }

    /**
     * 获取指定父节点路径 key 下所有需要的特征码。
     */
    public Set<String> collectFeatureCodesForParent(String parentPathKey) {
        Set<String> codes = new HashSet<>();
        if (inputMap != null) {
            Map<String, Set<String>> byChild = inputMap.get(parentPathKey);
            if (byChild != null) {
                byChild.values().forEach(codes::addAll);
            }
        }
        return codes;
    }

    /**
     * 收集所有输入映射中的特征码。
     */
    public Set<String> collectAllFeatureCodes() {
        Set<String> codes = new HashSet<>();
        if (inputMap != null) {
            for (Map<String, Set<String>> byChild : inputMap.values()) {
                for (Set<String> features : byChild.values()) {
                    codes.addAll(features);
                }
            }
        }
        return codes;
    }

    /**
     * 获取指定父路径 key + 子路径 key 需要的特征码集合（精确匹配）。
     * 返回 null 表示无映射。
     */
    public Set<String> getNeededFeatures(String parentPathKey, String childPathKey) {
        if (inputMap == null) {
            return null;
        }
        Map<String, Set<String>> byChild = inputMap.get(parentPathKey);
        if (byChild == null) {
            return null;
        }
        return byChild.get(childPathKey);
    }

    /**
     * 检查指定特征是否有任何实体目标需要。
     */
    public boolean isFeatureTargeted(String featureCode) {
        Set<String> targetIds = getTargetIdsForFeature(featureCode);
        return targetIds != null && !targetIds.isEmpty();
    }

    /**
     * 构建 pathKey 的便捷方法。
     */
    public static String pathKey(String levelName, String entityId) {
        return levelName + ":" + entityId;
    }

    // ==================== Build methods ====================

    private Map<String, Set<String>> buildFeatureTargetMap(Map<String, FeatureConfig> configMap) {
        Map<String, Set<String>> targetMap = new LinkedHashMap<>();
        if (inputMap == null) {
            return targetMap;
        }
        for (Map<String, Set<String>> byChild : inputMap.values()) {
            for (Map.Entry<String, Set<String>> e : byChild.entrySet()) {
                String childKey = e.getKey();
                for (String fc : e.getValue()) {
                    targetMap.computeIfAbsent(fc, k -> new LinkedHashSet<>()).add(childKey);
                }
            }
        }
        propagateTargets(targetMap, configMap);
        return targetMap;
    }

    private Map<String, Map<String, Set<String>>> buildFeatureChildParentMap(Map<String, FeatureConfig> configMap) {
        // 反转 inputMap: parent → child → features 变为 feature → child → parents
        Map<String, Map<String, Set<String>>> result = invertInputMap();
        if (result.isEmpty()) {
            return result;
        }
        propagateChildParentTargets(result, configMap);
        return result;
    }

    private Map<String, Map<String, Set<String>>> invertInputMap() {
        Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
        if (inputMap == null) {
            return result;
        }
        for (var parentEntry : inputMap.entrySet()) {
            String parentKey = parentEntry.getKey();
            for (var childEntry : parentEntry.getValue().entrySet()) {
                String childKey = childEntry.getKey();
                for (String fc : childEntry.getValue()) {
                    result.computeIfAbsent(fc, k -> new LinkedHashMap<>())
                            .computeIfAbsent(childKey, k -> new LinkedHashSet<>())
                            .add(parentKey);
                }
            }
        }
        return result;
    }

    private void propagateChildParentTargets(Map<String, Map<String, Set<String>>> result,
                                              Map<String, FeatureConfig> configMap) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Map<String, Set<String>>> newEntries = new LinkedHashMap<>();

            for (var fcEntry : result.entrySet()) {
                String fc = fcEntry.getKey();
                Map<String, Set<String>> childParentMap = fcEntry.getValue();
                FeatureConfig cfg = configMap.get(fc);

                if (cfg == null || cfg.getDependsOn() == null) {
                    continue;
                }

                for (String dep : cfg.getDependsOn()) {
                    Map<String, Set<String>> depExisting = result.getOrDefault(dep, Map.of());
                    for (var childEntry : childParentMap.entrySet()) {
                        String childKey = childEntry.getKey();
                        Set<String> neededParentKeys = childEntry.getValue();
                        Set<String> alreadyHas = depExisting.getOrDefault(childKey, Set.of());
                        for (String parentKey : neededParentKeys) {
                            if (!alreadyHas.contains(parentKey)) {
                                newEntries.computeIfAbsent(dep, k -> new LinkedHashMap<>())
                                        .computeIfAbsent(childKey, k2 -> new LinkedHashSet<>())
                                        .add(parentKey);
                                changed = true;
                            }
                        }
                    }
                }
            }

            newEntries.forEach((fc, childMap) -> {
                Map<String, Set<String>> target = result.computeIfAbsent(fc, k -> new LinkedHashMap<>());
                childMap.forEach((childKey, parentKeys) ->
                        target.computeIfAbsent(childKey, k -> new LinkedHashSet<>()).addAll(parentKeys));
            });
        }
    }

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
