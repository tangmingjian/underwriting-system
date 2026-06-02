package com.insurance.uw.feature.core.impl;

import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.common.enums.StorageLevel;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.service.FeatureDependencyResolver;
import com.insurance.uw.domain.service.FeatureResultCache;
import com.insurance.uw.feature.api.FeatureExtractionRequest;
import com.insurance.uw.feature.api.FeatureExtractionResult;
import com.insurance.uw.feature.api.FeatureExtractionService;
import com.insurance.uw.feature.core.handler.FeatureCalcHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * 特征取数服务实现 — 核心调度器
 *
 * 负责编排整个特征取数流程：
 * 1. 构建上下文树
 * 2. 传递依赖展开
 * 3. 拓扑排序
 * 4. 按层执行（同层并发、层间串行、同服务批处理）
 * 5. 内部上下文 → 外部 DTO 转换
 */
public class FeatureExtractionServiceImpl implements FeatureExtractionService {

    private final FeatureConfigRepository featureConfigRepository;
    private final FeatureDependencyResolver dependencyResolver;
    private final ExecutorService executor;
    private final Map<CalcType, FeatureCalcHandler> calcHandlers;
    private final FeatureResultCache resultCache;

    public FeatureExtractionServiceImpl(FeatureConfigRepository featureConfigRepository,
                                        ExecutorService executor,
                                        List<FeatureCalcHandler> handlers,
                                        FeatureResultCache resultCache) {
        this.featureConfigRepository = featureConfigRepository;
        this.dependencyResolver = new FeatureDependencyResolver();
        this.executor = executor;
        this.calcHandlers = new HashMap<>();
        for (FeatureCalcHandler h : handlers) {
            this.calcHandlers.put(h.getSupportedType(), h);
        }
        this.resultCache = resultCache;
    }

    @Override
    public FeatureExtractionResult extract(FeatureExtractionRequest request) {
        Order order = request.getOrder();
        OrderFeatureContext orderCtx = new OrderFeatureContext(order);

        // 注入逐保单隔离的特征映射（由调用方 RuleApplicationService 推导）
        if (request.getPolicyInsuredFeatureMap() != null) {
            orderCtx.setPolicyInsuredFeatureMap(request.getPolicyInsuredFeatureMap());
        }
        if (request.getPolicyApplicantFeatureMap() != null) {
            orderCtx.setPolicyApplicantFeatureMap(request.getPolicyApplicantFeatureMap());
        }

        Set<String> requestedCodes = request.getFeatureCodes();
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            return convertToResult(orderCtx);
        }

        // 按需迭代加载 + 传递依赖展开（BFS，只查依赖链上的配置，避免全表扫描）
        Map<String, FeatureConfig> configMap = loadConfigsWithDependencies(requestedCodes);
        Set<String> expandedCodes = new LinkedHashSet<>(configMap.keySet());

        // 拓扑排序
        List<Set<String>> layers = dependencyResolver.topoSort(expandedCodes, configMap);

        // 按拓扑层级依次执行
        for (Set<String> layer : layers) {
            executeLayer(orderCtx, layer, configMap,
                    request.getPolicyInsuredFeatureMap(),
                    request.getPolicyApplicantFeatureMap());
        }

        return convertToResult(orderCtx);
    }

    // ==================== 依赖展开 ====================

    /**
     * 传递依赖展开：请求 {B}，B 的 dependsOn = [A]，自动展开为 {A, B}
     */
    public Set<String> expandDependencies(Set<String> requested, Map<String, FeatureConfig> allConfigs) {
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

    // ==================== 配置加载 ====================

    /**
     * 从 requestedCodes 出发，BFS 迭代加载配置及其传递依赖。
     * 每轮只查当前层新发现的依赖码，避免全表扫描。
     */
    private Map<String, FeatureConfig> loadConfigsWithDependencies(Set<String> requestedCodes) {
        Map<String, FeatureConfig> configMap = new LinkedHashMap<>();
        Set<String> toLoad = new LinkedHashSet<>(requestedCodes);

        while (!toLoad.isEmpty()) {
            List<FeatureConfig> batch = featureConfigRepository.findByFeatureCodes(new ArrayList<>(toLoad));
            Set<String> nextToLoad = new LinkedHashSet<>();
            for (FeatureConfig fc : batch) {
                configMap.putIfAbsent(fc.getFeatureCode(), fc);
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

    /**
     * 执行一层特征：
     * - 无依赖的特征在同一层内并行执行
     * - 同层同服务 EXTERNAL_API 特征合并为一个批处理组
     */
    private void executeLayer(OrderFeatureContext orderCtx,
                              Set<String> layer,
                              Map<String, FeatureConfig> configMap,
                              Map<String, Map<String, Set<String>>> policyInsuredFeatureMap,
                              Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        Map<AggregationLevel, List<String>> byAgg = groupByAggregation(layer, configMap);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        futures.addAll(executeOrderLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.ORDER, List.of()), configMap, policyInsuredFeatureMap, policyApplicantFeatureMap));
        futures.addAll(executePolicyLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.POLICY, List.of()), configMap, policyInsuredFeatureMap, policyApplicantFeatureMap));
        futures.addAll(executeInsuredLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.INSURED, List.of()), configMap, policyInsuredFeatureMap));
        futures.addAll(executeApplicantLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.APPLICANT, List.of()), configMap, policyApplicantFeatureMap));

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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

    /**
     * 通用特征分组执行引擎：服务分组 → 过滤 → 批量/分发 → 存储。
     *
     * @param ctx          执行上下文，传递给 calcHandler
     * @param featureCodes 本层特征码列表
     * @param configMap    特征配置索引
     * @param needed       需要的特征集（null/empty = 不过滤，执行全部）
     * @param futures      并发任务收集器
     * @param storer       结果存储回调 (FeatureConfig, results) → void
     */
    private void executeGroups(Object ctx,
                               List<String> featureCodes,
                               Map<String, FeatureConfig> configMap,
                               Set<String> needed,
                               List<CompletableFuture<Void>> futures,
                               BiConsumer<FeatureConfig, Map<String, Object>> storer) {
        if (featureCodes.isEmpty()) {
            return;
        }

        Map<String, List<String>> groups = groupByServiceKey(featureCodes, configMap);

        for (List<String> group : groups.values()) {
            List<String> applicable = (needed == null || needed.isEmpty())
                    ? group : group.stream().filter(needed::contains).toList();
            if (applicable.isEmpty()) {
                continue;
            }

            List<FeatureConfig> cfgs = applicable.stream().map(configMap::get).toList();
            if (canBatch(applicable, cfgs)) {
                futures.add(CompletableFuture.runAsync(() -> {
                    Map<String, Map<String, Object>> batchResults =
                            calcHandlers.get(CalcType.EXTERNAL_API).executeBatch(ctx, cfgs);
                    for (String fc : applicable) {
                        Map<String, Object> results = batchResults.get(fc);
                        if (results != null) {
                            storer.accept(configMap.get(fc), results);
                        }
                    }
                }, executor));
            } else {
                for (String fc : applicable) {
                    FeatureConfig cfg = configMap.get(fc);
                    dispatchFeature(cfg, futures, () ->
                            executeOne(ctx, cfg, results -> storer.accept(cfg, results)));
                }
            }
        }
    }

    // ==================== ORDER 级 ====================

    /**
     * ORDER 级：整个订单执行一次，needed 为所有保单下被保人/投保人需要的特征并集+传递依赖。
     */
    private List<CompletableFuture<Void>> executeOrderLayer(OrderFeatureContext orderCtx,
                                                             List<String> featureCodes,
                                                             Map<String, FeatureConfig> configMap,
                                                             Map<String, Map<String, Set<String>>> policyInsuredFeatureMap,
                                                             Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        Set<String> needed = expandDependencies(
                collectAllNeeded(policyInsuredFeatureMap, policyApplicantFeatureMap), configMap);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        executeGroups(orderCtx, featureCodes, configMap, needed, futures,
                (fc, results) -> storeResults(orderCtx, fc, results));
        return futures;
    }

    private static Set<String> collectAllNeeded(
            Map<String, Map<String, Set<String>>> insuredMap,
            Map<String, Map<String, Set<String>>> applicantMap) {
        Set<String> directNeeded = new HashSet<>();
        if (insuredMap != null) {
            for (var byInsured : insuredMap.values()) {
                for (var features : byInsured.values()) {
                    directNeeded.addAll(features);
                }
            }
        }
        if (applicantMap != null) {
            for (var byApplicant : applicantMap.values()) {
                for (var features : byApplicant.values()) {
                    directNeeded.addAll(features);
                }
            }
        }
        return directNeeded;
    }

    // ==================== POLICY 级 ====================

    /**
     * POLICY 级：每个保单独立执行，needed 为该保单下被保人/投保人需要的特征并集+传递依赖。
     */
    private List<CompletableFuture<Void>> executePolicyLayer(OrderFeatureContext orderCtx,
                                                              List<String> featureCodes,
                                                              Map<String, FeatureConfig> configMap,
                                                              Map<String, Map<String, Set<String>>> policyInsuredFeatureMap,
                                                              Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            Set<String> needed = expandDependencies(
                    collectPolicyNeeded(polCtx.getPolicyId(), policyInsuredFeatureMap, policyApplicantFeatureMap),
                    configMap);
            executeGroups(polCtx, featureCodes, configMap, needed, futures,
                    (fc, results) -> storePolicyResults(polCtx, fc, results));
        }
        return futures;
    }

    private static Set<String> collectPolicyNeeded(String policyId,
                                                    Map<String, Map<String, Set<String>>> insuredMap,
                                                    Map<String, Map<String, Set<String>>> applicantMap) {
        Set<String> directNeeded = new HashSet<>();
        if (insuredMap != null) {
            var byInsured = insuredMap.getOrDefault(policyId, Map.of());
            byInsured.values().forEach(directNeeded::addAll);
        }
        if (applicantMap != null) {
            var byApplicant = applicantMap.getOrDefault(policyId, Map.of());
            byApplicant.values().forEach(directNeeded::addAll);
        }
        return directNeeded;
    }

    // ==================== INSURED 级 ====================

    /**
     * INSURED 级：每个被保人独立执行，按保单+被保人过滤所需特征。
     */
    private List<CompletableFuture<Void>> executeInsuredLayer(OrderFeatureContext orderCtx,
                                                               List<String> featureCodes,
                                                               Map<String, FeatureConfig> configMap,
                                                               Map<String, Map<String, Set<String>>> policyInsuredFeatureMap) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (InsuredFeatureContext insCtx : orderCtx.getAllInsuredContexts()) {
            Set<String> needed = (policyInsuredFeatureMap != null)
                    ? policyInsuredFeatureMap
                        .getOrDefault(insCtx.getPolicyContext().getPolicyId(), Map.of())
                        .getOrDefault(insCtx.getInsuredId(), null)
                    : null;
            executeGroups(insCtx, featureCodes, configMap, needed, futures,
                    (fc, results) -> storeInsuredResults(insCtx, fc, results));
        }
        return futures;
    }

    // ==================== APPLICANT 级 ====================

    /**
     * APPLICANT 级：每个投保人独立执行，按保单+投保人过滤所需特征。
     */
    private List<CompletableFuture<Void>> executeApplicantLayer(OrderFeatureContext orderCtx,
                                                                 List<String> featureCodes,
                                                                 Map<String, FeatureConfig> configMap,
                                                                 Map<String, Map<String, Set<String>>> policyApplicantFeatureMap) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
            if (appCtx == null) {
                continue;
            }
            Set<String> needed = (policyApplicantFeatureMap != null)
                    ? policyApplicantFeatureMap
                        .getOrDefault(polCtx.getPolicyId(), Map.of())
                        .getOrDefault(appCtx.getApplicantId(), null)
                    : null;
            executeGroups(appCtx, featureCodes, configMap, needed, futures,
                    (fc, results) -> storeApplicantResults(appCtx, fc, results));
        }
        return futures;
    }

    // ==================== 通用执行 & 批处理 ====================

    /**
     * 单个特征执行模板：查结果缓存 → 调用 handler → 存储结果 → 回写缓存。
     */
    private void executeOne(Object ctx, FeatureConfig fc, ResultStorer storer) {
        try {
            Map<String, Object> results = executeByCalcType(ctx, fc);
            if (results != null) {
                // 跨请求结果缓存：按 targetId 粒度
                Integer ttlSeconds = fc.getTtlSeconds();
                if (ttlSeconds != null && ttlSeconds > 0) {
                    for (Map.Entry<String, Object> entry : results.entrySet()) {
                        resultCache.put(fc.getFeatureCode(), entry.getKey(), entry.getValue(), ttlSeconds);
                    }
                }
                storer.store(results);
            }
        } catch (Exception e) {
            throw new RuntimeException("执行特征 " + fc.getFeatureCode() + " 失败: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ResultStorer {
        void store(Map<String, Object> results);
    }

    private static boolean canBatch(List<String> featureCodes, List<FeatureConfig> cfgs) {
        return featureCodes.size() > 1 && cfgs.get(0).getCalcType() == CalcType.EXTERNAL_API;
    }

    /**
     * 按计算类型分发：PARAM_MAPPING 同步执行（纯 CPU 操作，避免线程池调度开销），
     * 其他类型通过 CompletableFuture.runAsync 并发执行。
     */
    private void dispatchFeature(FeatureConfig cfg, List<CompletableFuture<Void>> futures,
                                  Runnable action) {
        if (cfg.getCalcType() == CalcType.PARAM_MAPPING) {
            action.run();
        } else {
            futures.add(CompletableFuture.runAsync(action, executor));
        }
    }

    // ==================== 按 calc_type 分发 ====================

    private Map<String, Object> executeByCalcType(Object ctx, FeatureConfig fc) {
        FeatureCalcHandler handler = calcHandlers.get(fc.getCalcType());
        if (handler == null) {
            throw new IllegalArgumentException("不支持的计算类型: " + fc.getCalcType());
        }
        return handler.execute(ctx, fc);
    }

    // ==================== 结果存储 ====================

    @SuppressWarnings("unchecked")
    private void storeResults(OrderFeatureContext ctx, FeatureConfig fc,
                              Map<String, Object> results) {
        StorageLevel level = fc.getStorageLevel();

        for (Map.Entry<String, Object> entry : results.entrySet()) {
            String targetId = entry.getKey();
            Object value = entry.getValue();

            Map<String, Object> featureMap = (value instanceof Map)
                    ? (Map<String, Object>) value
                    : Collections.singletonMap(fc.getFeatureCode(), value);

            switch (level) {
                case INSURED:
                    var insCtx = ctx.findInsuredCtx(targetId);
                    if (insCtx != null) insCtx.getAcquiredFeatures().putAll(featureMap);
                    break;
                case POLICY:
                    var polCtx = ctx.findPolicyCtx(targetId);
                    if (polCtx != null) polCtx.getPolicyFeatures().putAll(featureMap);
                    break;
                case APPLICANT:
                    var pCtx = ctx.findPolicyCtx(targetId);
                    if (pCtx != null) pCtx.getApplicantCtx().getFeatures().putAll(featureMap);
                    break;
                case ORDER:
                    ctx.getOrderFeatures().putAll(featureMap);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void storePolicyResults(PolicyFeatureContext polCtx, FeatureConfig fc,
                                    Map<String, Object> results) {
        StorageLevel level = fc.getStorageLevel();

        for (Map.Entry<String, Object> entry : results.entrySet()) {
            String targetId = entry.getKey();
            Object value = entry.getValue();

            Map<String, Object> featureMap = (value instanceof Map)
                    ? (Map<String, Object>) value
                    : Collections.singletonMap(fc.getFeatureCode(), value);

            switch (level) {
                case INSURED:
                    var insCtx = polCtx.getInsureds().stream()
                            .filter(ic -> ic.getInsuredId().equals(targetId))
                            .findFirst().orElse(null);
                    if (insCtx != null) insCtx.getAcquiredFeatures().putAll(featureMap);
                    break;
                case APPLICANT:
                    polCtx.getApplicantCtx().getFeatures().putAll(featureMap);
                    break;
                case POLICY:
                    polCtx.getPolicyFeatures().putAll(featureMap);
                    break;
                case ORDER:
                    polCtx.getOrderContext().getOrderFeatures().putAll(featureMap);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void storeInsuredResults(InsuredFeatureContext insCtx, FeatureConfig fc,
                                     Map<String, Object> results) {
        StorageLevel level = fc.getStorageLevel();

        for (Map.Entry<String, Object> entry : results.entrySet()) {
            Object value = entry.getValue();

            Map<String, Object> featureMap = (value instanceof Map)
                    ? (Map<String, Object>) value
                    : Collections.singletonMap(fc.getFeatureCode(), value);

            switch (level) {
                case INSURED:
                    insCtx.getAcquiredFeatures().putAll(featureMap);
                    break;
                case APPLICANT:
                    if (insCtx.getPolicyContext() != null) {
                        insCtx.getPolicyContext().getApplicantCtx().getFeatures().putAll(featureMap);
                    }
                    break;
                case POLICY:
                    if (insCtx.getPolicyContext() != null) {
                        insCtx.getPolicyContext().getPolicyFeatures().putAll(featureMap);
                    }
                    break;
                case ORDER:
                    if (insCtx.getOrderContext() != null) {
                        insCtx.getOrderContext().getOrderFeatures().putAll(featureMap);
                    }
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void storeApplicantResults(ApplicantFeatureContext appCtx, FeatureConfig fc,
                                       Map<String, Object> results) {
        StorageLevel level = fc.getStorageLevel();

        for (Map.Entry<String, Object> entry : results.entrySet()) {
            Object value = entry.getValue();

            Map<String, Object> featureMap = (value instanceof Map)
                    ? (Map<String, Object>) value
                    : Collections.singletonMap(fc.getFeatureCode(), value);

            switch (level) {
                case APPLICANT:
                    appCtx.getFeatures().putAll(featureMap);
                    break;
                case INSURED:
                    // APPLICANT 级聚合的结果不能路由到被保人（没有目标被保人信息）
                    break;
                case POLICY:
                    if (appCtx.getPolicyContext() != null) {
                        appCtx.getPolicyContext().getPolicyFeatures().putAll(featureMap);
                    }
                    break;
                case ORDER:
                    if (appCtx.getOrderContext() != null) {
                        appCtx.getOrderContext().getOrderFeatures().putAll(featureMap);
                    }
                    break;
            }
        }
    }

    // ==================== 内部上下文 → 外部结果 ====================

    /**
     * 将内部 OrderFeatureContext 树转换为扁平化的 FeatureExtractionResult
     */
    private FeatureExtractionResult convertToResult(OrderFeatureContext orderCtx) {
        FeatureExtractionResult result = new FeatureExtractionResult();

        // ORDER 级特征
        result.getOrderFeatures().putAll(orderCtx.getOrderFeatures());

        // 保单 / 投保人 / 被保人特征（扁平化）
        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            if (!polCtx.getPolicyFeatures().isEmpty()) {
                result.getPolicyFeatures().put(polCtx.getPolicyId(),
                        new HashMap<>(polCtx.getPolicyFeatures()));
            }

            ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
            if (appCtx != null && !appCtx.getFeatures().isEmpty()) {
                result.putApplicantFeature(polCtx.getPolicyId(), appCtx.getApplicantId(),
                        new HashMap<>(appCtx.getFeatures()));
            }

            for (InsuredFeatureContext insCtx : polCtx.getInsureds()) {
                if (!insCtx.getAcquiredFeatures().isEmpty()) {
                    result.putInsuredFeature(polCtx.getPolicyId(), insCtx.getInsuredId(),
                            new HashMap<>(insCtx.getAcquiredFeatures()));
                }
            }
        }

        return result;
    }
}
