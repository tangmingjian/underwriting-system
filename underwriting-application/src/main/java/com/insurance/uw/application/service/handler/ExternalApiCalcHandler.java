package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.GroovyMappingEngine;

import java.util.HashMap;
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
}
