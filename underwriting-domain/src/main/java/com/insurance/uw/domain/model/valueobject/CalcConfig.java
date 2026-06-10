package com.insurance.uw.domain.model.valueobject;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 特征计算配置值对象 —— 根据 calc_type 存储不同结构
 *
 * JSON 字段使用 snake_case，Java 字段使用 camelCase。
 * EXTERNAL_API 类型结构：
 * { "service": {...}, "input_script_id": "xxx", "output_script_id": "yyy" }
 */
public class CalcConfig {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 服务配置（EXTERNAL_API 类型） */
    @JsonProperty("service")
    private ServiceConfig service;

    /** 入参映射脚本标识 */
    @JsonProperty("input_script_id")
    private String inputScriptId;

    /** 出参映射脚本标识 */
    @JsonProperty("output_script_id")
    private String outputScriptId;

    /** 表达式计算脚本标识（EXPRESSION 类型） */
    @JsonProperty("expression_script_id")
    private String expressionScriptId;

    /** 入参取数路径（PARAM_MAPPING 类型），格式 {entityType}.{fieldName}，如 insured.age */
    @JsonProperty("source")
    private String source;

    public CalcConfig() {}

    public ServiceConfig getService() { return service; }
    public void setService(ServiceConfig service) { this.service = service; }

    public String getInputScriptId() { return inputScriptId; }
    public void setInputScriptId(String inputScriptId) { this.inputScriptId = inputScriptId; }

    public String getOutputScriptId() { return outputScriptId; }
    public void setOutputScriptId(String outputScriptId) { this.outputScriptId = outputScriptId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getExpressionScriptId() { return expressionScriptId; }
    public void setExpressionScriptId(String expressionScriptId) { this.expressionScriptId = expressionScriptId; }

    // ==================== JSON 序列化/反序列化工具方法 ====================

    public static CalcConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new CalcConfig();
        }
        try {
            return objectMapper.readValue(json, CalcConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("解析 calc_config 失败: " + json, e);
        }
    }

    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 calc_config 失败", e);
        }
    }

}
