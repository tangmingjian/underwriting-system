package com.insurance.uw.engine.core.service;

import com.insurance.uw.engine.core.context.ContextNode;
import com.insurance.uw.engine.core.enums.AggregationLevel;
import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.engine.core.handler.FeatureCalcHandler;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.repository.FeatureConfigRepository;
import com.insurance.uw.engine.core.routing.FeatureResultDispatcher;
import com.insurance.uw.engine.core.targeting.FeatureTargeting;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * 通用特征取数引擎 — 基于 ContextNode 树的可复用执行流水线。
 *
 * <p>核保系统、保全系统等只需实现各自的 ContextNode 树并提供 Repository，
 * 即可复用此引擎的全部特征计算逻辑。</p>
 *
 * <h3>流水线（4 阶段）</h3>
 * <ol>
 *   <li>加载配置（含依赖展开）</li>
 *   <li>构建派生目标映射</li>
 *   <li>拓扑排序 + 分层执行（递归遍历 ContextNode 树）</li>
 *   <li>结果扁平化输出</li>
 * </ol>
 */
public class FeatureExtractionEngine {

    private static final Logger LOG = Logger.getLogger(FeatureExtractionEngine.class.getName());

    private final FeatureConfigRepository configRepository;
    private final FeatureDependencyResolver dependencyResolver;
    private final ExecutorService executor;
    private final Map<CalcType, FeatureCalcHandler> calcHandlers;
    private final FeatureResultCache resultCache;

    public FeatureExtractionEngine(FeatureConfigRepository configRepository,
                                    FeatureDependencyResolver dependencyResolver,
                                    ExecutorService executor,
                                    List<FeatureCalcHandler> handlers,
                                    FeatureResultCache resultCache) {
        this.configRepository = configRepository;
        this.dependencyResolver = dependencyResolver;
        this.executor = executor;
        this.calcHandlers = new HashMap<>();
        for (FeatureCalcHandler h : handlers) {
            this.calcHandlers.put(h.getSupportedType(), h);
        }
        this.resultCache = resultCache;
    }

    /**
     * 执行特征提取。
     *
     * @param rootNode      上下文树根节点
     * @param requestedCodes 需要提取的特征码集合
     * @param targeting      特征-目标映射（已设置输入映射）
     * @return 按 ContextNode 树组织的特征结果（根节点的 featureStore 会被直接写入）
     */
    public void extract(ContextNode rootNode,
                        Set<String> requestedCodes,
                        FeatureTargeting targeting) {
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            LOG.info("[引擎] 无请求特征码，跳过");
            return;
        }
        LOG.info("[引擎] 特征提取开始, 请求特征码=" + requestedCodes);

        // Phase 1: 加载配置 + 依赖展开
        Map<String, FeatureConfig> configMap = loadConfigsWithDependencies(requestedCodes);
        Set<String> expandedCodes = new LinkedHashSet<>(configMap.keySet());
        LOG.info("[引擎] 配置加载完成, 含依赖共 " + expandedCodes.size() + " 个特征");

        // Phase 2: 构建派生映射
        targeting.buildDerivedMaps(configMap);

        // Phase 3: 拓扑排序 + 分层执行
        List<Set<String>> layers = dependencyResolver.topoSort(expandedCodes, configMap);
        LOG.info("[引擎] 拓扑排序完成, 共 " + layers.size() + " 层");

        FeatureResultDispatcher dispatcher = new FeatureResultDispatcher(rootNode, targeting);

        int layerIdx = 0;
        for (Set<String> layer : layers) {
            LOG.info("[引擎] 执行第 " + (++layerIdx) + "/" + layers.size() + " 层: " + layer);
            executeLayer(rootNode, layer, configMap, targeting, dispatcher);
        }

        LOG.info("[引擎] 特征提取完成");
    }

    // ==================== 配置加载 ====================

    private Map<String, FeatureConfig> loadConfigsWithDependencies(Set<String> requestedCodes) {
        Map<String, FeatureConfig> configMap = new LinkedHashMap<>();
        Set<String> toLoad = new LinkedHashSet<>(requestedCodes);

        while (!toLoad.isEmpty()) {
            List<FeatureConfig> batch = configRepository.findByFeatureCodes(new ArrayList<>(toLoad));
            for (FeatureConfig fc : batch) {
                configMap.putIfAbsent(fc.getFeatureCode(), fc);
            }
            Set<String> nextToLoad = new LinkedHashSet<>();
            for (FeatureConfig fc : batch) {
                if (fc.getDependsOn() != null) {
                    for (String dep : fc.getDependsOn()) {
                        if (!configMap.containsKey(dep)) {
                            nextToLoad.add(dep);
                        }
                    }
                }
            }
            toLoad = nextToLoad;
        }
        return configMap;
    }

    // ==================== 分层执行 ====================

    private void executeLayer(ContextNode rootNode,
                               Set<String> layer,
                               Map<String, FeatureConfig> configMap,
                               FeatureTargeting targeting,
                               FeatureResultDispatcher dispatcher) {
        Map<AggregationLevel, List<String>> byAgg = groupByAggregation(layer, configMap);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (AggregationLevel agg : AggregationLevel.values()) {
            List<String> codes = byAgg.getOrDefault(agg, List.of());
            if (codes.isEmpty()) continue;

            if (agg.depth() == AggregationLevel.ORDER.depth()) {
                // ORDER 层级：只执行一次（根节点）
                Set<String> needed = targeting != null
                        ? expandDependencies(targeting.collectAllFeatureCodes(), configMap)
                        : null;
                executeGroups(rootNode, codes, configMap, needed, futures, dispatcher);
            } else {
                // 其他层级：收集 rootNode 子树中所有匹配的 ContextNode
                List<? extends ContextNode> nodes = rootNode.collectDescendants(agg.name());
                if (!nodes.isEmpty()) {
                    futures.addAll(executeForNodes(nodes, codes, configMap, targeting, dispatcher));
                }
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private List<CompletableFuture<Void>> executeForNodes(
            List<? extends ContextNode> nodes,
            List<String> featureCodes,
            Map<String, FeatureConfig> configMap,
            FeatureTargeting targeting,
            FeatureResultDispatcher dispatcher) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ContextNode node : nodes) {
            Set<String> needed = targeting != null
                    ? targeting.getNeededFeatures(
                            node.getParent() != null
                                    ? FeatureTargeting.pathKey(node.getParent().getLevelName(), node.getParent().getNodeId())
                                    : node.getNodeId(),
                            FeatureTargeting.pathKey(node.getLevelName(), node.getNodeId()))
                    : null;
            executeGroups(node, featureCodes, configMap, needed, futures, dispatcher);
        }
        return futures;
    }

    // ==================== AggregationLevel 分组 ====================

    private static Map<AggregationLevel, List<String>> groupByAggregation(
            Set<String> layer, Map<String, FeatureConfig> configMap) {
        Map<AggregationLevel, List<String>> byAgg = new LinkedHashMap<>();
        for (String fc : layer) {
            FeatureConfig cfg = configMap.get(fc);
            AggregationLevel agg = cfg != null ? cfg.getAggregation() : AggregationLevel.ORDER;
            byAgg.computeIfAbsent(agg, k -> new ArrayList<>()).add(fc);
        }
        return byAgg;
    }

    private Map<String, List<String>> groupByServiceKey(List<String> featureCodes,
                                                         Map<String, FeatureConfig> configMap) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String fc : featureCodes) {
            FeatureConfig cfg = configMap.get(fc);
            String key = cfg != null
                    ? cfg.getCalcType() + ":" + cfg.getServiceKey()
                    : "UNKNOWN:" + fc;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(fc);
        }
        return groups;
    }

    // ==================== 通用分组执行引擎 ====================

    private void executeGroups(Object ctx,
                                List<String> featureCodes,
                                Map<String, FeatureConfig> configMap,
                                Set<String> needed,
                                List<CompletableFuture<Void>> futures,
                                FeatureResultDispatcher dispatcher) {
        if (featureCodes.isEmpty()) {
            return;
        }

        Map<String, List<String>> groups = groupByServiceKey(featureCodes, configMap);

        for (List<String> group : groups.values()) {
            List<String> applicable = (needed == null)
                    ? group : group.stream().filter(needed::contains).collect(java.util.stream.Collectors.toList());
            if (applicable.isEmpty()) {
                continue;
            }

            List<FeatureConfig> cfgs = applicable.stream().map(configMap::get).collect(java.util.stream.Collectors.toList());
            if (canBatch(applicable, cfgs)) {
                List<String> batchCodes = applicable;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        Map<String, Map<String, Object>> batchResults =
                                calcHandlers.get(CalcType.EXTERNAL_API).executeBatch(ctx, cfgs);
                        for (String fc : batchCodes) {
                            Map<String, Object> results = batchResults.get(fc);
                            if (results != null) {
                                FeatureConfig cfg = configMap.get(fc);
                                dispatcher.dispatch(ctx, cfg, results);
                                cacheResults(fc, cfg, results);
                            }
                        }
                    } catch (Exception e) {
                        LOG.severe("[引擎] 批处理失败: " + e.getMessage());
                        throw new RuntimeException(e);
                    }
                }, executor));
            } else {
                for (String fc : applicable) {
                    FeatureConfig cfg = configMap.get(fc);
                    dispatchFeature(cfg, futures, () -> executeOne(ctx, cfg, dispatcher));
                }
            }
        }
    }

    // ==================== 单个特征执行 ====================

    private void executeOne(Object ctx, FeatureConfig fc, FeatureResultDispatcher dispatcher) {
        try {
            Map<String, Object> results = executeByCalcType(ctx, fc);
            if (results != null) {
                cacheResults(fc.getFeatureCode(), fc, results);
                dispatcher.dispatch(ctx, fc, results);
            }
        } catch (Exception e) {
            LOG.severe("[异常] 执行特征失败: feature=" + fc.getFeatureCode()
                    + " calcType=" + fc.getCalcType() + " error=" + e.getMessage());
            throw new RuntimeException("执行特征 " + fc.getFeatureCode() + " 失败: " + e.getMessage(), e);
        }
    }

    private void cacheResults(String featureCode, FeatureConfig fc, Map<String, Object> results) {
        Integer ttlSeconds = fc.getTtlSeconds();
        if (ttlSeconds != null && ttlSeconds > 0) {
            for (var entry : results.entrySet()) {
                resultCache.put(featureCode, entry.getKey(), entry.getValue(), ttlSeconds);
            }
        }
    }

    private Map<String, Object> executeByCalcType(Object ctx, FeatureConfig fc) {
        FeatureCalcHandler handler = calcHandlers.get(fc.getCalcType());
        if (handler == null) {
            throw new IllegalArgumentException("不支持的计算类型: " + fc.getCalcType());
        }
        return handler.execute(ctx, fc);
    }

    private void dispatchFeature(FeatureConfig cfg, List<CompletableFuture<Void>> futures,
                                  Runnable action) {
        if (cfg.getCalcType() == CalcType.PARAM_MAPPING) {
            action.run();
        } else {
            futures.add(CompletableFuture.runAsync(action, executor));
        }
    }

    private static boolean canBatch(List<String> featureCodes, List<FeatureConfig> cfgs) {
        return featureCodes.size() > 1 && !cfgs.isEmpty()
                && cfgs.get(0).getCalcType() == CalcType.EXTERNAL_API;
    }

    // ==================== 依赖展开 ====================

    private Set<String> expandDependencies(Set<String> requested, Map<String, FeatureConfig> allConfigs) {
        Set<String> expanded = new LinkedHashSet<>(requested);
        Queue<String> queue = new LinkedList<>(requested);
        while (!queue.isEmpty()) {
            FeatureConfig fc = allConfigs.get(queue.poll());
            if (fc != null && fc.getDependsOn() != null) {
                for (String dep : fc.getDependsOn()) {
                    if (expanded.add(dep)) {
                        queue.add(dep);
                    }
                }
            }
        }
        return expanded;
    }
}
