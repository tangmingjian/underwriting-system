package com.insurance.uw.engine.core.handler;

import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.model.entity.FeatureScript;
import com.insurance.uw.engine.core.model.valueobject.CalcConfig;
import com.insurance.uw.engine.core.model.valueobject.ServiceConfig;
import com.insurance.uw.engine.core.repository.FeatureScriptRepository;
import com.insurance.uw.engine.core.service.DownstreamApiClient;
import com.insurance.uw.engine.core.service.GroovyMappingEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXTERNAL_API 类型处理器：加载脚本 → Groovy 拼装请求 → HTTP 调用 → Groovy 提取响应。
 * 全程使用 Object ctx，完全领域无关。
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

        Map<String, FeatureScript> localCache = new HashMap<>();

        String inputScriptId = calcConfig.getInputScriptId();
        FeatureScript inScript = loadScript(inputScriptId, fc.getFeatureCode(), localCache, "入参脚本");

        String outputScriptId = calcConfig.getOutputScriptId();
        FeatureScript outScript = loadScript(outputScriptId, fc.getFeatureCode(), localCache, "出参脚本");

        Object rawRequest = groovyEngine.invoke(
                inputScriptId, inScript.getScriptText(), "buildRequest", ctx);

        final Object rawResponse;
        if (rawRequest instanceof List) {
            List<Map<String, Object>> batchRequests = (List<Map<String, Object>>) rawRequest;
            List<Map<String, Object>> batchResponses = new ArrayList<>();
            for (Map<String, Object> req : batchRequests) {
                batchResponses.add(apiClient.call(serviceConfig, req));
            }
            rawResponse = batchResponses;
        } else {
            Map<String, Object> request = (Map<String, Object>) rawRequest;
            rawResponse = apiClient.call(serviceConfig, request);
        }

        return invokeAndDispatch(fc, outScript, rawResponse, ctx);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Map<String, Object>> executeBatch(Object ctx, List<FeatureConfig> features) {
        if (features.isEmpty()) {
            return Map.of();
        }

        ServiceConfig serviceConfig = features.get(0).getCalcConfig().getService();

        Map<String, Object> mergedRequest = null;
        List<FeatureConfig> singleFeatures = new ArrayList<>();
        List<FeatureScript> singleOutScripts = new ArrayList<>();
        Map<String, FeatureScript> localCache = new HashMap<>();
        Map<String, Map<String, Object>> aggregated = new LinkedHashMap<>();

        for (FeatureConfig fc : features) {
            CalcConfig calcConfig = fc.getCalcConfig();

            String inputScriptId = calcConfig.getInputScriptId();
            FeatureScript inScript = loadScript(inputScriptId, fc.getFeatureCode(), localCache, "入参脚本");

            String outputScriptId = calcConfig.getOutputScriptId();
            FeatureScript outScript = loadScript(outputScriptId, fc.getFeatureCode(), localCache, "出参脚本");

            Object rawRequest = groovyEngine.invoke(
                    inputScriptId, inScript.getScriptText(), "buildRequest", ctx);

            if (rawRequest instanceof List) {
                aggregated.put(fc.getFeatureCode(), execute(ctx, fc));
            } else {
                Map<String, Object> request = (Map<String, Object>) rawRequest;
                mergedRequest = deepMerge(mergedRequest, request);
                singleFeatures.add(fc);
                singleOutScripts.add(outScript);
            }
        }

        if (mergedRequest != null) {
            Map<String, Object> response = apiClient.call(serviceConfig, mergedRequest);

            for (int i = 0; i < singleFeatures.size(); i++) {
                FeatureConfig fc = singleFeatures.get(i);
                FeatureScript outScript = singleOutScripts.get(i);
                aggregated.put(fc.getFeatureCode(), invokeAndDispatch(fc, outScript, response, ctx));
            }
        }

        return aggregated;
    }

    private FeatureScript loadScript(String scriptId, String featureCode,
                                      Map<String, FeatureScript> localCache, String scriptLabel) {
        return localCache.computeIfAbsent(scriptId, id ->
                scriptRepository.findByScriptId(id)
                        .orElseThrow(() -> new IllegalArgumentException(
                                scriptLabel + "不存在: " + id + "（特征: " + featureCode + "）")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeAndDispatch(FeatureConfig fc, FeatureScript outScript,
                                                   Object rawResponse, Object ctx) {
        CalcConfig calcConfig = fc.getCalcConfig();

        Map<String, Map<String, Object>> featureResults = (Map<String, Map<String, Object>>) groovyEngine.invoke(
                calcConfig.getOutputScriptId(), outScript.getScriptText(), "extractFeatures", rawResponse, ctx);

        Map<String, Object> wrapped = new HashMap<>();
        if (featureResults != null) {
            featureResults.forEach((targetId, featureData) -> {
                Map<String, Object> inner = new HashMap<>();
                inner.put(fc.getFeatureCode(), featureData);
                wrapped.put(targetId, inner);
            });
        }
        return wrapped;
    }

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
