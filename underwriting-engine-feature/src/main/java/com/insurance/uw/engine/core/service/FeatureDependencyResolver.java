package com.insurance.uw.engine.core.service;

import com.insurance.uw.engine.core.enums.AggregationLevel;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;

import java.util.*;

/**
 * 特征依赖解析器 — 基于拓扑排序（Kahn算法）生成分层执行计划
 */
public class FeatureDependencyResolver {

    /**
     * 对特征码列表进行拓扑排序，返回分层执行计划
     *
     * @param featureCodes 所有需要执行的特征码集合
     * @param configMap    特征码 -> 特征配置的映射
     * @return 分层列表，每层内的特征可并发执行
     * @throws IllegalStateException 存在循环依赖时抛出
     */
    public List<Set<String>> topoSort(Set<String> featureCodes, Map<String, FeatureConfig> configMap) {
        // 入度表：特征码 -> 依赖数量
        Map<String, Integer> inDegree = new HashMap<>();
        // 被依赖关系：特征码 -> 依赖它的特征集合
        Map<String, Set<String>> dependents = new HashMap<>();

        for (String fc : featureCodes) {
            inDegree.putIfAbsent(fc, 0);
            FeatureConfig cfg = configMap.get(fc);
            if (cfg != null && cfg.getDependsOn() != null) {
                for (String dep : cfg.getDependsOn()) {
                    if (!featureCodes.contains(dep)) {
                        throw new IllegalStateException(
                                "特征 " + fc + " 依赖 " + dep + "，但该特征未包含在本次执行计划中");
                    }
                    // 校验依赖方向：只允许依赖上级或同级特征，禁止向下依赖
                    FeatureConfig depCfg = configMap.get(dep);
                    if (depCfg != null) {
                        validateDependencyDirection(fc, cfg.getAggregation(), dep, depCfg.getAggregation());
                    }
                    inDegree.merge(fc, 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new HashSet<>()).add(fc);
                }
            }
        }

        // Kahn 算法
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }

        List<Set<String>> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Set<String> layer = new LinkedHashSet<>(queue);
            result.add(layer);
            Queue<String> nextQueue = new LinkedList<>();
            for (String node : layer) {
                Set<String> depList = dependents.get(node);
                if (depList != null) {
                    for (String dep : depList) {
                        int newDegree = inDegree.merge(dep, -1, Integer::sum);
                        if (newDegree == 0) {
                            nextQueue.add(dep);
                        }
                    }
                }
            }
            queue = nextQueue;
        }

        // 检查循环依赖
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() > 0) {
                throw new IllegalStateException("存在循环依赖，涉及特征: " + e.getKey());
            }
        }

        return result;
    }

    /**
     * 校验依赖方向：只允许依赖上级（聚合范围更大）或同级特征。
     * 聚合层级高 → 低：ORDER > POLICY > APPLICANT > INSURED。
     * 数值越大表示聚合范围越大（层级越高）。
     */
    void validateDependencyDirection(String featureCode, AggregationLevel featureLevel,
                                     String dependencyCode, AggregationLevel dependencyLevel) {
        if (featureLevel == null || dependencyLevel == null) {
            return;
        }
        int featureRank = rank(featureLevel);
        int dependencyRank = rank(dependencyLevel);
        if (dependencyRank < featureRank) {
            throw new IllegalStateException(
                    "特征 " + featureCode + "（" + featureLevel + "级）不允许依赖 "
                            + dependencyCode + "（" + dependencyLevel + "级），"
                            + "只能依赖上级或同级特征");
        }
    }

    /**
     * 聚合层级排名：数值越大层级越高（聚合范围越大）。
     * ORDER(3) > POLICY(2) > APPLICANT(1) > INSURED(0)
     */
    private int rank(AggregationLevel level) {
        switch (level) {
            case ORDER: return 3;
            case POLICY: return 2;
            case APPLICANT: return 1;
            case INSURED: return 0;
            default: throw new IllegalArgumentException("Unknown AggregationLevel: " + level);
        }
    }

}
