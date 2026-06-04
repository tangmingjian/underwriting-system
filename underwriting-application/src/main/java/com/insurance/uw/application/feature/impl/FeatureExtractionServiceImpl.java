package com.insurance.uw.application.feature.impl;

import com.insurance.uw.application.feature.handler.FeatureCalcHandler;
import com.insurance.uw.application.feature.routing.FeatureResultDispatcher;
import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.FeatureTargeting;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.service.FeatureDependencyResolver;
import com.insurance.uw.domain.service.FeatureResultCache;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * 特征取数服务实现 — 核心调度器
 *
 * <p>负责编排整个特征取数流程（4 阶段流水线）：</p>
 * <ol>
 *   <li><b>Calculation</b>: 按拓扑层级执行 handler（同层并发、层间串行、同服务批处理）</li>
 *   <li><b>Normalization</b>: handler 返回标准化 key 的结果 Map（ORDER_KEY | insuredId | policyId | SELF_KEY）</li>
 *   <li><b>Dispatch</b>: {@link FeatureResultDispatcher} 根据 AggregationLevel × StorageLevel 将结果路由到上下文树</li>
 *   <li><b>Storage</b>: 结果写入 OrderFeatureContext 树，最后通过 convertToResult 扁平化输出</li>
 * </ol>
 *
 * <p>上下文树结构：OrderFeatureContext → PolicyFeatureContext → InsuredFeatureContext / ApplicantFeatureContext</p>
 */
public class FeatureExtractionServiceImpl implements FeatureExtractionService {

    private static final Logger LOG = Logger.getLogger(FeatureExtractionServiceImpl.class.getName());

    private final FeatureConfigRepository featureConfigRepository;
    private final FeatureDependencyResolver dependencyResolver;
    private final ExecutorService executor;
    private final Map<CalcType, FeatureCalcHandler> calcHandlers;
    private final FeatureResultCache resultCache;

    public FeatureExtractionServiceImpl(FeatureConfigRepository featureConfigRepository,
                                        FeatureDependencyResolver dependencyResolver,
                                        ExecutorService executor,
                                        List<FeatureCalcHandler> handlers,
                                        FeatureResultCache resultCache) {
        this.featureConfigRepository = featureConfigRepository;
        this.dependencyResolver = dependencyResolver;
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
        String orderId = order.getId();
        Set<String> requestedCodes = request.getFeatureCodes();

        // ---- 请求概览 ----
        LOG.info("========== 特征提取开始: orderId=" + orderId + " ==========");
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            LOG.info("[请求] orderId=" + orderId + " 无请求特征码，返回空结果");
            return convertToResult(new OrderFeatureContext(order));
        }
        logRequestDetail(request, orderId);

        // Phase 1: 构建上下文树 + 注入特征-目标映射
        OrderFeatureContext orderCtx = new OrderFeatureContext(order);
        FeatureTargeting ft = new FeatureTargeting();
        ft.setInputMaps(request.getPolicyInsuredFeatureMap(), request.getPolicyApplicantFeatureMap());
        orderCtx.setFeatureTargeting(ft);

        // Phase 2: 配置加载 + 依赖展开（BFS，只查依赖链）
        Map<String, FeatureConfig> configMap = loadConfigsWithDependencies(requestedCodes);
        Set<String> expandedCodes = new LinkedHashSet<>(configMap.keySet());
        logConfigLoaded(configMap, requestedCodes);

        // Phase 3: 构建派生映射（必须在分层执行前完成，避免并发竞态）
        ft.buildDerivedMaps(configMap);

        // Phase 4: 创建分发器 → 拓扑排序 → 分层执行
        FeatureResultDispatcher dispatcher = new FeatureResultDispatcher(orderCtx, ft);
        List<Set<String>> layers = dependencyResolver.topoSort(expandedCodes, configMap);
        LOG.info("[拓扑] orderId=" + orderId + " 共 " + layers.size() + " 层, 特征码(含依赖)=" + expandedCodes);

        int layerIdx = 0;
        for (Set<String> layer : layers) {
            LOG.info("[执行] orderId=" + orderId + " 第 " + (++layerIdx) + "/" + layers.size()
                    + " 层: " + layer);
            executeLayer(orderCtx, layer, configMap, dispatcher);
        }

        // ---- 输出结果 ----
        FeatureExtractionResult result = convertToResult(orderCtx);
        logFinalResult(result, orderId);
        LOG.info("========== 特征提取完成: orderId=" + orderId + " ==========");
        return result;
    }

    /** 打印请求详情：哪个保单的哪个被保人/投保人需要哪些特征 */
    private void logRequestDetail(FeatureExtractionRequest request, String orderId) {
        Map<String, Map<String, Set<String>>> insuredMap = request.getPolicyInsuredFeatureMap();
        Map<String, Map<String, Set<String>>> applicantMap = request.getPolicyApplicantFeatureMap();

        if (insuredMap != null) {
            insuredMap.forEach((policyId, byInsured) ->
                    byInsured.forEach((insuredId, features) ->
                            LOG.info("[需求] orderId=" + orderId + " policyId=" + policyId
                                    + " 被保人=" + insuredId + " → " + features)));
        }
        if (applicantMap != null) {
            applicantMap.forEach((policyId, byApplicant) ->
                    byApplicant.forEach((applicantId, features) ->
                            LOG.info("[需求] orderId=" + orderId + " policyId=" + policyId
                                    + " 投保人=" + applicantId + " → " + features)));
        }
    }

    /** 打印加载到的特征配置 */
    private void logConfigLoaded(Map<String, FeatureConfig> configMap, Set<String> requestedCodes) {
        configMap.forEach((code, fc) -> {
            boolean isRequested = requestedCodes.contains(code);
            LOG.info("[配置] " + code
                    + " calcType=" + fc.getCalcType()
                    + " aggregation=" + fc.getAggregation()
                    + " storage=" + fc.getStorageLevel()
                    + (isRequested ? "" : " (依赖展开)")
                    + (fc.getDependsOn() != null && !fc.getDependsOn().isEmpty()
                        ? " dependsOn=" + fc.getDependsOn() : ""));
        });
    }

    /** 打印最终提取结果 */
    private void logFinalResult(FeatureExtractionResult result, String orderId) {
        LOG.info("[结果] orderId=" + orderId + " === 最终提取结果 ===");

        Map<String, Object> orderFeats = result.getOrderFeatures();
        if (!orderFeats.isEmpty()) {
            LOG.info("[结果] 订单级特征: " + orderFeats);
        }

        Map<String, Map<String, Object>> policyFeats = result.getPolicyFeatures();
        if (!policyFeats.isEmpty()) {
            policyFeats.forEach((policyId, features) ->
                    LOG.info("[结果] 保单特征 policyId=" + policyId + ": " + features));
        }

        Map<String, Map<String, Map<String, Object>>> insuredFeats = result.getInsuredFeatures();
        if (!insuredFeats.isEmpty()) {
            insuredFeats.forEach((policyId, byInsured) ->
                    byInsured.forEach((insuredId, features) ->
                            LOG.info("[结果] 被保人特征 policyId=" + policyId
                                    + " insuredId=" + insuredId + ": " + features)));
        }

        Map<String, Map<String, Map<String, Object>>> applicantFeats = result.getApplicantFeatures();
        if (!applicantFeats.isEmpty()) {
            applicantFeats.forEach((policyId, byApplicant) ->
                    byApplicant.forEach((applicantId, features) ->
                            LOG.info("[结果] 投保人特征 policyId=" + policyId
                                    + " applicantId=" + applicantId + ": " + features)));
        }

        int total = orderFeats.size() + policyFeats.values().stream().mapToInt(Map::size).sum()
                + insuredFeats.values().stream().flatMap(m -> m.values().stream()).mapToInt(Map::size).sum()
                + applicantFeats.values().stream().flatMap(m -> m.values().stream()).mapToInt(Map::size).sum();
        LOG.info("[结果] orderId=" + orderId + " 共计 " + total + " 个特征值"
                + " (order:" + orderFeats.size()
                + " policy:" + policyFeats.size()
                + " insured:" + result.getInsuredFeatures().size()
                + " applicant:" + result.getApplicantFeatures().size() + ")");
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
                              FeatureResultDispatcher dispatcher) {
        Map<AggregationLevel, List<String>> byAgg = groupByAggregation(layer, configMap);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        futures.addAll(executeOrderLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.ORDER, List.of()), configMap, dispatcher));
        futures.addAll(executePolicyLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.POLICY, List.of()), configMap, dispatcher));
        futures.addAll(executeInsuredLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.INSURED, List.of()), configMap, dispatcher));
        futures.addAll(executeApplicantLayer(orderCtx, byAgg.getOrDefault(AggregationLevel.APPLICANT, List.of()), configMap, dispatcher));

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
     * @param dispatcher   结果分发器
     */
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
                    ? group : group.stream().filter(needed::contains).toList();
            if (applicable.isEmpty()) {
                continue;
            }

            List<FeatureConfig> cfgs = applicable.stream().map(configMap::get).toList();
            if (canBatch(applicable, cfgs)) {
                List<String> batchCodes = applicable;
                futures.add(CompletableFuture.runAsync(() -> {
                    LOG.info("[批处理] 合并调用: " + batchCodes + " ctx=" + describeContext(ctx));
                    Map<String, Map<String, Object>> batchResults =
                            calcHandlers.get(CalcType.EXTERNAL_API).executeBatch(ctx, cfgs);
                    for (String fc : batchCodes) {
                        Map<String, Object> results = batchResults.get(fc);
                        if (results != null) {
                            FeatureConfig cfg = configMap.get(fc);
                            // 打印批处理中每个特征的计算结果
                            String ctxDesc = describeContext(ctx);
                            results.forEach((targetKey, rawValue) ->
                                    LOG.info("[取值] " + fc
                                            + " calcType=" + cfg.getCalcType()
                                            + " agg=" + cfg.getAggregation()
                                            + " storage=" + cfg.getStorageLevel()
                                            + " ctx=" + ctxDesc
                                            + " targetKey=" + targetKey
                                            + " → " + rawValue));
                            dispatcher.dispatch(ctx, cfg, results);
                            Integer ttlSeconds = cfg.getTtlSeconds();
                            if (ttlSeconds != null && ttlSeconds > 0) {
                                for (var entry : results.entrySet()) {
                                    resultCache.put(fc, entry.getKey(), entry.getValue(), ttlSeconds);
                                }
                            }
                        }
                    }
                }, executor));
            } else {
                for (String fc : applicable) {
                    FeatureConfig cfg = configMap.get(fc);
                    dispatchFeature(cfg, futures, () ->
                            executeOne(ctx, cfg, dispatcher));
                }
            }
        }
    }

    // ==================== ORDER 级 ====================

    /**
     * ORDER 级：整个订单执行一次，needed 为所有保单下被保人/投保人需要的特征并集+传递依赖。
     * 按需过滤：跳过没有实体需要的特征，避免无效计算。
     */
    private List<CompletableFuture<Void>> executeOrderLayer(OrderFeatureContext orderCtx,
                                                             List<String> featureCodes,
                                                             Map<String, FeatureConfig> configMap,
                                                             FeatureResultDispatcher dispatcher) {
        FeatureTargeting ft = orderCtx.getFeatureTargeting();
        Set<String> needed = expandDependencies(
                ft != null ? ft.collectAllFeatureCodes() : Set.of(), configMap);

        // 按需过滤：只执行有实体需要的 ORDER 特征，避免无效计算
        List<String> filteredCodes = featureCodes;
        if (ft != null) {
            filteredCodes = featureCodes.stream()
                    .filter(ft::isFeatureTargeted)
                    .toList();
            List<String> skipped = featureCodes.stream()
                    .filter(fc -> !ft.isFeatureTargeted(fc))
                    .toList();
            if (!skipped.isEmpty()) {
                LOG.fine(() -> "ORDER 层跳过（无实体需要）: " + skipped);
            }
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        executeGroups(orderCtx, filteredCodes, configMap, needed, futures, dispatcher);
        return futures;
    }

    // ==================== POLICY 级 ====================

    /**
     * POLICY 级：每个保单独立执行，needed 为该保单下被保人/投保人需要的特征并集+传递依赖。
     */
    private List<CompletableFuture<Void>> executePolicyLayer(OrderFeatureContext orderCtx,
                                                              List<String> featureCodes,
                                                              Map<String, FeatureConfig> configMap,
                                                              FeatureResultDispatcher dispatcher) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        FeatureTargeting ft = orderCtx.getFeatureTargeting();
        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            Set<String> needed = expandDependencies(
                    ft != null ? ft.collectFeatureCodesForPolicy(polCtx.getPolicyId()) : Set.of(),
                    configMap);
            executeGroups(polCtx, featureCodes, configMap, needed, futures, dispatcher);
        }
        return futures;
    }

    // ==================== INSURED 级 ====================

    /**
     * INSURED 级：每个被保人独立执行，按保单+被保人过滤所需特征。
     */
    private List<CompletableFuture<Void>> executeInsuredLayer(OrderFeatureContext orderCtx,
                                                               List<String> featureCodes,
                                                               Map<String, FeatureConfig> configMap,
                                                               FeatureResultDispatcher dispatcher) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        FeatureTargeting ft = orderCtx.getFeatureTargeting();
        for (InsuredFeatureContext insCtx : orderCtx.getAllInsuredContexts()) {
            Set<String> needed = ft != null
                    ? ft.getNeededFeaturesForInsured(insCtx.getPolicyContext().getPolicyId(), insCtx.getInsuredId())
                    : null;
            executeGroups(insCtx, featureCodes, configMap, needed, futures, dispatcher);
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
                                                                 FeatureResultDispatcher dispatcher) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        FeatureTargeting ft = orderCtx.getFeatureTargeting();
        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
            if (appCtx == null) {
                continue;
            }
            Set<String> needed = ft != null
                    ? ft.getNeededFeaturesForApplicant(polCtx.getPolicyId(), appCtx.getApplicantId())
                    : null;
            executeGroups(appCtx, featureCodes, configMap, needed, futures, dispatcher);
        }
        return futures;
    }

    // ==================== 通用执行 & 批处理 ====================

    /**
     * 单个特征执行模板：调用 handler → 打印结果 → 分发 → 回写缓存。
     * PARAM_MAPPING 同步执行，其他类型通过 dispatchFeature 异步执行。
     */
    private void executeOne(Object ctx, FeatureConfig fc, FeatureResultDispatcher dispatcher) {
        String fcCode = fc.getFeatureCode();
        try {
            Map<String, Object> results = executeByCalcType(ctx, fc);
            if (results != null) {
                // 打印计算结果：谁取了什么特征，值是什么
                String ctxDesc = describeContext(ctx);
                results.forEach((targetKey, rawValue) -> {
                    Object displayValue = rawValue instanceof Map
                            ? rawValue
                            : Collections.singletonMap(fcCode, rawValue);
                    LOG.info("[取值] " + fcCode
                            + " calcType=" + fc.getCalcType()
                            + " agg=" + fc.getAggregation()
                            + " storage=" + fc.getStorageLevel()
                            + " ctx=" + ctxDesc
                            + " targetKey=" + targetKey
                            + " → " + displayValue);
                });

                int ttlSeconds = fc.getTtlSeconds() != null ? fc.getTtlSeconds() : 0;
                if (ttlSeconds > 0) {
                    for (Map.Entry<String, Object> entry : results.entrySet()) {
                        resultCache.put(fcCode, entry.getKey(), entry.getValue(), ttlSeconds);
                    }
                    LOG.fine(() -> "[缓存] " + fcCode + " TTL=" + ttlSeconds + "s");
                }
                dispatcher.dispatch(ctx, fc, results);
            } else {
                LOG.fine(() -> "[取值] " + fcCode + " 返回 null，跳过");
            }
        } catch (Exception e) {
            LOG.severe("[异常] 执行特征失败: feature=" + fcCode
                    + " calcType=" + fc.getCalcType() + " error=" + e.getMessage());
            throw new RuntimeException("执行特征 " + fcCode + " 失败: " + e.getMessage(), e);
        }
    }

    /** 描述执行上下文（用于日志） */
    private static String describeContext(Object ctx) {
        if (ctx instanceof OrderFeatureContext oc) {
            return "order(" + oc.getOrderId() + ")";
        } else if (ctx instanceof PolicyFeatureContext pc) {
            return "policy(" + pc.getPolicyId() + ")";
        } else if (ctx instanceof InsuredFeatureContext ic) {
            String polId = ic.getPolicyContext() != null ? ic.getPolicyContext().getPolicyId() : "?";
            return "insured(" + ic.getInsuredId() + "@" + polId + ")";
        } else if (ctx instanceof ApplicantFeatureContext ac) {
            String polId = ac.getPolicyContext() != null ? ac.getPolicyContext().getPolicyId() : "?";
            return "applicant(" + ac.getApplicantId() + "@" + polId + ")";
        }
        return ctx.getClass().getSimpleName();
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
