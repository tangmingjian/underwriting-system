package com.insurance.uw.application.service;

import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.common.enums.StorageLevel;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.FeatureDependencyResolver;
import com.insurance.uw.domain.service.GroovyMappingEngine;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 核保应用服务 — 核心调度器
 *
 * 负责编排整个特征取数流程：
 * 1. 构建上下文树
 * 2. 收集所需特征码
 * 3. 拓扑排序
 * 4. 按层执行（同层并发、层间串行）
 * 5. 根据 calc_type 调度不同的计算逻辑（当前聚焦 EXTERNAL_API）
 */
public class UnderwritingApplicationService {

    private final FeatureConfigRepository featureConfigRepository;
    private final FeatureScriptRepository scriptRepository;
    private final UnderwritingRuleRepository ruleRepository;
    private final FeatureDependencyResolver dependencyResolver;
    private final DownstreamApiClient apiClient;
    private final GroovyMappingEngine groovyEngine;
    private final ExecutorService executor;

    public UnderwritingApplicationService(FeatureConfigRepository featureConfigRepository,
                                          FeatureScriptRepository scriptRepository,
                                          UnderwritingRuleRepository ruleRepository,
                                          DownstreamApiClient apiClient,
                                          GroovyMappingEngine groovyEngine,
                                          ExecutorService executor) {
        this.featureConfigRepository = featureConfigRepository;
        this.scriptRepository = scriptRepository;
        this.ruleRepository = ruleRepository;
        this.dependencyResolver = new FeatureDependencyResolver();
        this.apiClient = apiClient;
        this.groovyEngine = groovyEngine;
        this.executor = executor;
    }

    /**
     * 对订单执行完整的核保特征取数流程
     */
    public OrderFeatureContext processOrder(Order order) {
        OrderFeatureContext orderCtx = new OrderFeatureContext(order);

        // 构建特征→被保人/保单映射（product→rule→feature 链路推算）
        buildFeatureInsuredMapping(orderCtx);

        Set<String> featureCodes = collectFeatureCodes(orderCtx);
        if (featureCodes.isEmpty()) {
            return orderCtx;
        }

        Map<String, FeatureConfig> configMap = loadConfigs(featureCodes);

        // 拓扑排序
        List<Set<String>> layers = dependencyResolver.topoSort(featureCodes, configMap);

        // 按拓扑层级依次执行
        for (Set<String> layer : layers) {
            executeLayer(orderCtx, layer, configMap);
        }

        return orderCtx;
    }

    /**
     * 构建特征→被保人/保单映射（product→rule→feature 链路推算）。
     * 遍历每个 Policy，取 productCode，匹配规则后解析 feature_codes，
     * 确定哪些 insured 需要哪些 feature。
     *
     * 向后兼容：product_code 为 NULL 的规则适用于所有产品。
     */
    private void buildFeatureInsuredMapping(OrderFeatureContext orderCtx) {
        List<UnderwritingRule> allRules = ruleRepository.findAllEnabled();

        Map<String, Set<String>> featureToInsuredIds = new HashMap<>();
        Map<String, Set<String>> featureToPolicyIds = new HashMap<>();

        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            String productCode = polCtx.getProductCode();
            Set<String> policyInsuredIds = polCtx.getInsureds().stream()
                    .map(InsuredFeatureContext::getInsuredId)
                    .collect(Collectors.toSet());

            for (UnderwritingRule rule : allRules) {
                // backward compat: product_code = null → applies to all products
                if (rule.getProductCode() != null && !rule.getProductCode().equals(productCode)) {
                    continue;
                }
                String featureCodesStr = rule.getFeatureCodes();
                if (featureCodesStr == null || featureCodesStr.isBlank()) {
                    continue;
                }
                for (String fc : featureCodesStr.split(",")) {
                    fc = fc.trim();
                    if (!fc.isEmpty()) {
                        featureToInsuredIds.computeIfAbsent(fc, k -> new HashSet<>()).addAll(policyInsuredIds);
                        featureToPolicyIds.computeIfAbsent(fc, k -> new HashSet<>()).add(polCtx.getPolicyId());
                    }
                }
            }
        }

        orderCtx.setFeatureInsuredMapping(featureToInsuredIds);
        orderCtx.setFeaturePolicyMapping(featureToPolicyIds);
    }

    private Set<String> collectFeatureCodes(OrderFeatureContext orderCtx) {
        Set<String> codes = new LinkedHashSet<>();
        List<FeatureConfig> allFeatures = featureConfigRepository.findAllEnabled();
        for (FeatureConfig fc : allFeatures) {
            codes.add(fc.getFeatureCode());
        }
        return codes;
    }

    private Map<String, FeatureConfig> loadConfigs(Set<String> featureCodes) {
        Map<String, FeatureConfig> map = new HashMap<>();
        List<FeatureConfig> configs = featureConfigRepository.findByFeatureCodes(new ArrayList<>(featureCodes));
        for (FeatureConfig fc : configs) {
            map.put(fc.getFeatureCode(), fc);
        }
        return map;
    }

    /**
     * 执行一层特征：
     * - 无依赖的特征在同一层内并行执行（使用统一线程池）
     * - 有依赖关系的特征通过拓扑排序分布在不同层，层间串行保证依赖顺序
     */
    private void executeLayer(OrderFeatureContext orderCtx,
                              Set<String> layer,
                              Map<String, FeatureConfig> configMap) {

        Map<Boolean, List<String>> grouped = new HashMap<>();
        for (String fc : layer) {
            FeatureConfig cfg = configMap.get(fc);
            boolean isOrder = cfg != null && cfg.getAggregation() == AggregationLevel.ORDER;
            grouped.computeIfAbsent(isOrder, k -> new ArrayList<>()).add(fc);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // ORDER 级特征 — 并行提交
        for (String fc : grouped.getOrDefault(true, Collections.emptyList())) {
            futures.add(CompletableFuture.runAsync(() ->
                    executeOrderFeature(orderCtx, configMap.get(fc)), executor));
        }

        // POLICY 级特征 — 每个保单的每个特征并行提交
        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            for (String fc : grouped.getOrDefault(false, Collections.emptyList())) {
                futures.add(CompletableFuture.runAsync(() ->
                        executePolicyFeature(polCtx, configMap.get(fc)), executor));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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

    // ==================== 按 calc_type 分发 ====================

    /**
     * 根据 calc_type 选择计算逻辑，返回特征结果 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeByCalcType(Object ctx, FeatureConfig fc) {
        CalcType calcType = fc.getCalcType();
        if (calcType == null) {
            throw new IllegalArgumentException("特征 " + fc.getFeatureCode() + " 未配置 calc_type");
        }

        switch (calcType) {
            case EXTERNAL_API:
                return executeExternalApi(ctx, fc);
            case EXPRESSION:
                // TODO: SpEL 表达式计算
                throw new UnsupportedOperationException("EXPRESSION 类型暂未实现");
            case PARAM_MAPPING:
                // TODO: 参数映射取数
                throw new UnsupportedOperationException("PARAM_MAPPING 类型暂未实现");
            case DATABASE_QUERY:
                // TODO: 数据库查询取数
                throw new UnsupportedOperationException("DATABASE_QUERY 类型暂未实现");
            case COMPOSITE:
                // TODO: 复合特征计算
                throw new UnsupportedOperationException("COMPOSITE 类型暂未实现");
            default:
                throw new IllegalArgumentException("不支持的计算类型: " + calcType);
        }
    }

    /**
     * EXTERNAL_API 类型：从 t_feature_script 加载脚本 → Groovy 拼装请求 → HTTP 调用 → Groovy 提取响应
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeExternalApi(Object ctx, FeatureConfig fc) {
        CalcConfig calcConfig = fc.getCalcConfig();
        ServiceConfig serviceConfig = calcConfig.getService();
        if (serviceConfig == null) {
            throw new IllegalArgumentException("特征 " + fc.getFeatureCode() + " calc_config.service 未配置");
        }

        // 1. 加载入参脚本
        String inputScriptId = calcConfig.getInputScriptId();
        FeatureScript inScript = scriptRepository.findByScriptId(inputScriptId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "入参脚本不存在: " + inputScriptId + "（特征: " + fc.getFeatureCode() + "）"));

        // 2. 加载出参脚本
        String outputScriptId = calcConfig.getOutputScriptId();
        FeatureScript outScript = scriptRepository.findByScriptId(outputScriptId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "出参脚本不存在: " + outputScriptId + "（特征: " + fc.getFeatureCode() + "）"));

        // 3. Groovy buildRequest
        Map<String, Object> request = (Map<String, Object>) groovyEngine.invoke(
                inputScriptId, inScript.getScriptText(), "buildRequest", ctx);

        // 4. HTTP 调用
        Map<String, Object> response = apiClient.call(serviceConfig, request);

        // 5. Groovy extractFeatures
        Map<String, Map<String, Object>> featureResults = (Map<String, Map<String, Object>>) groovyEngine.invoke(
                outputScriptId, outScript.getScriptText(), "extractFeatures", response, ctx);

        // 6. 展平为 Map
        Map<String, Object> result = new HashMap<>();
        if (featureResults != null) {
            featureResults.forEach((targetId, featureMap) -> result.put(targetId, featureMap));
        }
        return result;
    }

    // ==================== 结果存储 ====================

    @SuppressWarnings("unchecked")
    private void storeResults(OrderFeatureContext ctx, FeatureConfig fc,
                              Map<String, Object> results) {
        StorageLevel level = fc.getStorageLevel();

        for (Map.Entry<String, Object> entry : results.entrySet()) {
            String targetId = entry.getKey();
            Object value = entry.getValue();

            // 如果值是 Map，按特征码写入
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

}
