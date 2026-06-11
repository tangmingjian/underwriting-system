package com.insurance.uw.application.feature.impl;

import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.domain.context.*;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.service.FeatureExtractionEngine;
import com.insurance.uw.engine.core.targeting.FeatureTargeting;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;

import java.util.*;
import java.util.logging.Logger;

/**
 * 特征取数服务实现 — 委托 {@link FeatureExtractionEngine} 执行通用流水线。
 *
 * <p>核保系统只需实现 ContextNode 树并提供适配 Repository，即可复用引擎的全部特征计算逻辑。</p>
 */
public class FeatureExtractionServiceImpl implements FeatureExtractionService {

    private static final Logger LOG = Logger.getLogger(FeatureExtractionServiceImpl.class.getName());

    private final FeatureExtractionEngine engine;

    public FeatureExtractionServiceImpl(FeatureExtractionEngine engine) {
        this.engine = engine;
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

        // Phase 1: 构建上下文树 + 注入领域 FeatureTargeting（供 convertToResult 过滤用）
        OrderFeatureContext orderCtx = new OrderFeatureContext(order);
        com.insurance.uw.domain.context.FeatureTargeting domainFt =
                new com.insurance.uw.domain.context.FeatureTargeting();
        domainFt.setInputMaps(request.getPolicyInsuredFeatureMap(), request.getPolicyApplicantFeatureMap());
        orderCtx.setFeatureTargeting(domainFt);

        // Phase 2: 转换 targeting（domain typed-map → engine path-keyed）
        FeatureTargeting engFt = toEngineTargeting(request);

        // Phase 3: 委托引擎执行（配置加载、依赖展开、派生映射、拓扑排序、分层执行、结果分发）
        engine.extract(orderCtx, requestedCodes, engFt);

        // Phase 4: 扁平化输出（复用现有 convertToResult + domain FeatureTargeting）
        FeatureExtractionResult result = convertToResult(orderCtx);
        logFinalResult(result, orderId);
        LOG.info("========== 特征提取完成: orderId=" + orderId + " ==========");
        return result;
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

    // ==================== Targeting 转换（domain → engine） ====================

    private static FeatureTargeting toEngineTargeting(FeatureExtractionRequest request) {
        FeatureTargeting engFt = new FeatureTargeting();
        Map<String, Map<String, Set<String>>> inputMap = new LinkedHashMap<>();

        // 被保人映射：policyId → {INSURED:insuredId → features}
        Map<String, Map<String, Set<String>>> insuredMap = request.getPolicyInsuredFeatureMap();
        if (insuredMap != null) {
            for (var policyEntry : insuredMap.entrySet()) {
                String parentKey = FeatureTargeting.pathKey("POLICY", policyEntry.getKey());
                for (var insEntry : policyEntry.getValue().entrySet()) {
                    String childKey = FeatureTargeting.pathKey("INSURED", insEntry.getKey());
                    inputMap.computeIfAbsent(parentKey, k -> new LinkedHashMap<>())
                            .put(childKey, insEntry.getValue());
                }
            }
        }

        // 投保人映射：policyId → {APPLICANT:applicantId → features}
        Map<String, Map<String, Set<String>>> applicantMap = request.getPolicyApplicantFeatureMap();
        if (applicantMap != null) {
            for (var policyEntry : applicantMap.entrySet()) {
                String parentKey = FeatureTargeting.pathKey("POLICY", policyEntry.getKey());
                for (var appEntry : policyEntry.getValue().entrySet()) {
                    String childKey = FeatureTargeting.pathKey("APPLICANT", appEntry.getKey());
                    inputMap.computeIfAbsent(parentKey, k -> new LinkedHashMap<>())
                            .put(childKey, appEntry.getValue());
                }
            }
        }

        engFt.setInputMap(inputMap);
        return engFt;
    }

    // ==================== 日志 ====================

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
    @SuppressWarnings("unused")
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

    // ==================== 内部上下文 → 外部结果 ====================

    /**
     * 将内部 OrderFeatureContext 树转换为扁平化的 FeatureExtractionResult。
     * <p>
     * 按入参中每个实体的需求逐实体过滤，确保同保单下被保人 1 只要 A、被保人 2 只要 B 时不会互相串特征。
     * 依赖展开的中间特征（未被任何实体直接请求）也会被过滤掉。
     */
    private FeatureExtractionResult convertToResult(OrderFeatureContext orderCtx) {
        FeatureExtractionResult result = new FeatureExtractionResult();
        com.insurance.uw.domain.context.FeatureTargeting ft = orderCtx.getFeatureTargeting();

        // ORDER 级特征：按全局入参过滤
        Set<String> allRequested = ft != null ? ft.collectAllFeatureCodes() : Set.of();
        orderCtx.getOrderFeatures().forEach((fc, val) -> {
            if (allRequested.contains(fc)) {
                result.getOrderFeatures().put(fc, val);
            }
        });

        for (PolicyFeatureContext polCtx : orderCtx.getPolicies()) {
            String policyId = polCtx.getPolicyId();
            Set<String> policyRequested = ft != null
                    ? ft.collectFeatureCodesForPolicy(policyId) : Set.of();

            // 保单级特征：按该保单下所有实体的需求过滤
            Map<String, Object> polFeats = filterRequested(polCtx.getPolicyFeatures(), policyRequested);
            if (!polFeats.isEmpty()) {
                result.getPolicyFeatures().put(policyId, polFeats);
            }

            // 投保人特征：按该投保人自己的入参需求过滤
            ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
            if (appCtx != null) {
                Set<String> appNeeded = ft != null
                        ? ft.getNeededFeaturesForApplicant(policyId, appCtx.getApplicantId())
                        : null;
                Map<String, Object> appFeats = filterRequested(appCtx.getFeatures(), appNeeded);
                if (!appFeats.isEmpty()) {
                    result.putApplicantFeature(policyId, appCtx.getApplicantId(), appFeats);
                }
            }

            // 被保人特征：按每个被保人自己的入参需求过滤
            for (InsuredFeatureContext insCtx : polCtx.getInsureds()) {
                Set<String> insNeeded = ft != null
                        ? ft.getNeededFeaturesForInsured(policyId, insCtx.getInsuredId())
                        : null;
                Map<String, Object> insFeats = filterRequested(insCtx.getAcquiredFeatures(), insNeeded);
                if (!insFeats.isEmpty()) {
                    result.putInsuredFeature(policyId, insCtx.getInsuredId(), insFeats);
                }
            }
        }

        return result;
    }

    /**
     * 按 allowed 集合过滤特征 Map。
     * allowed 为 null 时不过滤（无映射场景），allowed 为空集时全部过滤。
     */
    private static Map<String, Object> filterRequested(Map<String, Object> features,
                                                        Set<String> allowed) {
        if (allowed == null) {
            return new HashMap<>(features);
        }
        Map<String, Object> filtered = new HashMap<>(features);
        filtered.keySet().retainAll(allowed);
        return filtered;
    }
}
