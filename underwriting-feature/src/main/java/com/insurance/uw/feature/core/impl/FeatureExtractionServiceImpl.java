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
import com.insurance.uw.feature.api.FeatureExtractionRequest;
import com.insurance.uw.feature.api.FeatureExtractionResult;
import com.insurance.uw.feature.api.FeatureExtractionService;
import com.insurance.uw.feature.core.handler.FeatureCalcHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

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

    public FeatureExtractionServiceImpl(FeatureConfigRepository featureConfigRepository,
                                        ExecutorService executor,
                                        List<FeatureCalcHandler> handlers) {
        this.featureConfigRepository = featureConfigRepository;
        this.dependencyResolver = new FeatureDependencyResolver();
        this.executor = executor;
        this.calcHandlers = new HashMap<>();
        for (FeatureCalcHandler h : handlers) {
            this.calcHandlers.put(h.getSupportedType(), h);
        }
    }

    @Override
    public FeatureExtractionResult extract(FeatureExtractionRequest request) {
        Order order = request.getOrder();
        OrderFeatureContext orderCtx = new OrderFeatureContext(order);

        // 注入特征→被保人/保单映射（由调用方 RuleApplicationService 推导）
        if (request.getFeatureToInsuredIds() != null) {
            orderCtx.setFeatureInsuredMapping(request.getFeatureToInsuredIds());
        }
        if (request.getFeatureToPolicyIds() != null) {
            orderCtx.setFeaturePolicyMapping(request.getFeatureToPolicyIds());
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
            executeLayer(orderCtx, layer, configMap);
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
     * - ORDER/POLICY/INSURED/APPLICANT 各级分开处理
     * - 同层同服务 EXTERNAL_API 特征合并为一个批处理组
     */
    private void executeLayer(OrderFeatureContext orderCtx,
                              Set<String> layer,
                              Map<String, FeatureConfig> configMap) {

        // 按 AggregationLevel 分组
        Map<AggregationLevel, List<String>> byAgg = new LinkedHashMap<>();
        for (String fc : layer) {
            FeatureConfig cfg = configMap.get(fc);
            AggregationLevel agg = cfg != null ? cfg.getAggregation() : AggregationLevel.ORDER;
            byAgg.computeIfAbsent(agg, k -> new ArrayList<>()).add(fc);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // ORDER 级特征
        List<String> orderFeatures = byAgg.getOrDefault(AggregationLevel.ORDER, Collections.emptyList());
        if (!orderFeatures.isEmpty()) {
            Map<String, List<String>> orderGroups = groupByServiceKey(orderFeatures, configMap);
            for (Map.Entry<String, List<String>> entry : orderGroups.entrySet()) {
                List<String> groupFeatures = entry.getValue();
                List<FeatureConfig> cfgs = groupFeatures.stream().map(configMap::get).toList();
                boolean canBatch = groupFeatures.size() > 1
                        && cfgs.get(0).getCalcType() == CalcType.EXTERNAL_API;
                if (canBatch) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        Map<String, Map<String, Object>> batchResults =
                                calcHandlers.get(CalcType.EXTERNAL_API).executeBatch(orderCtx, cfgs);
                        batchResults.forEach((fc, results) -> {
                            FeatureConfig cfg = configMap.get(fc);
                            if (results != null) storeResults(orderCtx, cfg, results);
                        });
                    }, executor));
                } else {
                    for (String fc : groupFeatures) {
                        futures.add(CompletableFuture.runAsync(() ->
                                executeOrderFeature(orderCtx, configMap.get(fc)), executor));
                    }
                }
            }
        }

        // POLICY 级特征 — 每个保单的每个特征并行提交
        List<String> policyFeatures = byAgg.getOrDefault(AggregationLevel.POLICY, Collections.emptyList());
        if (!policyFeatures.isEmpty()) {
            Map<String, List<String>> groups = groupByServiceKey(policyFeatures, configMap);

            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                List<String> groupFeatures = entry.getValue();
                FeatureConfig firstCfg = configMap.get(groupFeatures.get(0));
                boolean canBatch = groupFeatures.size() > 1
                        && firstCfg.getCalcType() == CalcType.EXTERNAL_API;

                for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
                    if (canBatch) {
                        futures.add(CompletableFuture.runAsync(() ->
                                executePolicyFeatureBatch(polCtx, groupFeatures, configMap), executor));
                    } else {
                        for (String fc : groupFeatures) {
                            futures.add(CompletableFuture.runAsync(() ->
                                    executePolicyFeature(polCtx, configMap.get(fc)), executor));
                        }
                    }
                }
            }
        }

        // INSURED 级特征 — 每个被保人独立执行
        List<String> insuredFeatures = byAgg.getOrDefault(AggregationLevel.INSURED, Collections.emptyList());
        if (!insuredFeatures.isEmpty()) {
            for (InsuredFeatureContext insCtx : orderCtx.getAllInsuredContexts()) {
                for (String fc : insuredFeatures) {
                    futures.add(CompletableFuture.runAsync(() ->
                            executeInsuredFeature(insCtx, configMap.get(fc)), executor));
                }
            }
        }

        // APPLICANT 级特征 — 每个投保人独立执行
        List<String> applicantFeatures = byAgg.getOrDefault(AggregationLevel.APPLICANT, Collections.emptyList());
        if (!applicantFeatures.isEmpty()) {
            for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
                ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
                if (appCtx == null) continue;
                for (String fc : applicantFeatures) {
                    futures.add(CompletableFuture.runAsync(() ->
                            executeApplicantFeature(appCtx, configMap.get(fc)), executor));
                }
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 按 (calcType, serviceKey) 分组，同组特征在批处理时可合并为一次调用
     */
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

    // ==================== ORDER 级特征执行 ====================

    private void executeOrderFeature(OrderFeatureContext ctx, FeatureConfig fc) {
        try {
            Map<String, Object> results = executeByCalcType(ctx, fc);
            if (results != null) {
                storeResults(ctx, fc, results);
            }
        } catch (Exception e) {
            throw new RuntimeException("执行特征 " + fc.getFeatureCode() + " 失败: " + e.getMessage(), e);
        }
    }

    // ==================== POLICY 级特征执行 ====================

    private void executePolicyFeature(PolicyFeatureContext polCtx, FeatureConfig fc) {
        try {
            Map<String, Object> results = executeByCalcType(polCtx, fc);
            if (results != null) {
                storePolicyResults(polCtx, fc, results);
            }
        } catch (Exception e) {
            throw new RuntimeException("执行特征 " + fc.getFeatureCode() + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * POLICY 级同组 EXTERNAL_API 特征批处理：合并为一次 HTTP 调用
     */
    private void executePolicyFeatureBatch(PolicyFeatureContext polCtx,
                                           List<String> featureCodes,
                                           Map<String, FeatureConfig> configMap) {
        List<FeatureConfig> cfgs = featureCodes.stream()
                .map(configMap::get)
                .filter(Objects::nonNull)
                .toList();
        if (cfgs.isEmpty()) return;

        try {
            FeatureCalcHandler handler = calcHandlers.get(cfgs.get(0).getCalcType());
            Map<String, Map<String, Object>> batchResults = handler.executeBatch(polCtx, cfgs);
            batchResults.forEach((fc, results) -> {
                FeatureConfig cfg = configMap.get(fc);
                if (results != null) storePolicyResults(polCtx, cfg, results);
            });
        } catch (Exception e) {
            throw new RuntimeException("批处理特征失败: " + e.getMessage(), e);
        }
    }

    // ==================== INSURED 级特征执行 ====================

    private void executeInsuredFeature(InsuredFeatureContext insCtx, FeatureConfig fc) {
        try {
            Map<String, Object> results = executeByCalcType(insCtx, fc);
            if (results != null) {
                storeInsuredResults(insCtx, fc, results);
            }
        } catch (Exception e) {
            throw new RuntimeException("执行特征 " + fc.getFeatureCode() + " 失败: " + e.getMessage(), e);
        }
    }

    // ==================== APPLICANT 级特征执行 ====================

    private void executeApplicantFeature(ApplicantFeatureContext appCtx, FeatureConfig fc) {
        try {
            Map<String, Object> results = executeByCalcType(appCtx, fc);
            if (results != null) {
                storeApplicantResults(appCtx, fc, results);
            }
        } catch (Exception e) {
            throw new RuntimeException("执行特征 " + fc.getFeatureCode() + " 失败: " + e.getMessage(), e);
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
                result.getApplicantFeatures().put(appCtx.getApplicantId(),
                        new HashMap<>(appCtx.getFeatures()));
            }

            for (InsuredFeatureContext insCtx : polCtx.getInsureds()) {
                if (!insCtx.getAcquiredFeatures().isEmpty()) {
                    result.getInsuredFeatures().put(insCtx.getInsuredId(),
                            new HashMap<>(insCtx.getAcquiredFeatures()));
                }
            }
        }

        return result;
    }
}
