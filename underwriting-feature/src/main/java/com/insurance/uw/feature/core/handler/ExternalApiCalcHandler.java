package com.insurance.uw.feature.core.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.GroovyMappingEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXTERNAL_API 类型处理器：加载脚本 → Groovy 拼装请求 → HTTP 调用 → Groovy 提取响应。
 */
public class ExternalApiCalcHandler implements FeatureCalcHandler {

    private final FeatureScriptRepository scriptRepository;
    private final GroovyMappingEngine groovyEngine;
    private final DownstreamApiClient apiClient;

    public ExternalApiCalcHandler(FeatureScriptRepository scriptRepository,
                                  GroovyMappingEngine groovyEngine,
                                  DownstreamApiClient apiClient) {
        this.scriptRepository = scriptRepository;
        this.groovyEngine = groovyEngine;
        this.apiClient = apiClient;
    }

    @Override
    public CalcType getSupportedType() {
        return CalcType.EXTERNAL_API;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
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

        // 6. 用 featureCode 包裹每个 targetId 的结果
        Map<String, Object> result = new HashMap<>();
        if (featureResults != null) {
            featureResults.forEach((targetId, featureData) -> {
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put(fc.getFeatureCode(), featureData);
                result.put(targetId, wrapped);
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Map<String, Object>> executeBatch(Object ctx, List<FeatureConfig> features) {
        if (features.isEmpty()) {
            return Map.of();
        }

        // 1. 取第一个特征的 ServiceConfig 用于实际 HTTP 调用
        ServiceConfig serviceConfig = features.get(0).getCalcConfig().getService();

        // 2. 对每个特征加载脚本、拼装请求
        Map<String, Object> mergedRequest = null;
        List<FeatureScript> outputScripts = new ArrayList<>();

        for (FeatureConfig fc : features) {
            CalcConfig calcConfig = fc.getCalcConfig();

            // 加载入参脚本
            String inputScriptId = calcConfig.getInputScriptId();
            FeatureScript inScript = scriptRepository.findByScriptId(inputScriptId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "入参脚本不存在: " + inputScriptId + "（特征: " + fc.getFeatureCode() + "）"));

            // 加载出参脚本（暂存，稍后逐特征 extractFeatures）
            String outputScriptId = calcConfig.getOutputScriptId();
            FeatureScript outScript = scriptRepository.findByScriptId(outputScriptId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "出参脚本不存在: " + outputScriptId + "（特征: " + fc.getFeatureCode() + "）"));
            outputScripts.add(outScript);

            // Groovy buildRequest
            Map<String, Object> request = (Map<String, Object>) groovyEngine.invoke(
                    inputScriptId, inScript.getScriptText(), "buildRequest", ctx);

            // 深度合并
            mergedRequest = deepMerge(mergedRequest, request);
        }

        // 3. 单次 HTTP 调用
        Map<String, Object> response = apiClient.call(serviceConfig, mergedRequest);

        // 4. 对每个特征调用 extractFeatures，聚合结果
        Map<String, Map<String, Object>> aggregated = new LinkedHashMap<>();
        for (int i = 0; i < features.size(); i++) {
            FeatureConfig fc = features.get(i);
            FeatureScript outScript = outputScripts.get(i);
            CalcConfig calcConfig = fc.getCalcConfig();

            Map<String, Map<String, Object>> featureResults = (Map<String, Map<String, Object>>) groovyEngine.invoke(
                    calcConfig.getOutputScriptId(), outScript.getScriptText(), "extractFeatures", response, ctx);

            // 用 featureCode 包裹每个 targetId 的结果
            Map<String, Object> wrapped = new HashMap<>();
            if (featureResults != null) {
                featureResults.forEach((targetId, featureData) -> {
                    Map<String, Object> inner = new HashMap<>();
                    inner.put(fc.getFeatureCode(), featureData);
                    wrapped.put(targetId, inner);
                });
            }
            aggregated.put(fc.getFeatureCode(), wrapped);
        }

        return aggregated;
    }

    /**
     * 深度合并两个请求 Map：
     * - 两个 Map 都有同一个 key → 如果都是 Map 则递归合并，如果都是 List 则拼接，否则后者覆盖前者
     * - base 为 null 时直接返回 overlay（防御）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        if (base == null) {
            return overlay != null ? new LinkedHashMap<>(overlay) : null;
        }
        if (overlay == null) {
            return base;
        }
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (result.containsKey(key)) {
                Object existing = result.get(key);
                if (existing instanceof Map && value instanceof Map) {
                    result.put(key, deepMerge((Map<String, Object>) existing, (Map<String, Object>) value));
                } else if (existing instanceof List && value instanceof List) {
                    List<Object> merged = new ArrayList<>((List<Object>) existing);
                    merged.addAll((List<Object>) value);
                    result.put(key, merged);
                } else {
                    result.put(key, value);
                }
            } else {
                result.put(key, value);
            }
        }
        return result;
    }
}
