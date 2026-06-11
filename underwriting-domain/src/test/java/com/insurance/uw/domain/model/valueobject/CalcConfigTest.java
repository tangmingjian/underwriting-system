package com.insurance.uw.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CalcConfig - JSON 序列化/反序列化")
class CalcConfigTest {

    @Nested
    @DisplayName("fromJson 反序列化")
    class Deserialization {

        @Test
        @DisplayName("null JSON → 返回空的 CalcConfig")
        void nullJson() {
            CalcConfig result = CalcConfig.fromJson(null);
            assertThat(result).isNotNull();
            assertThat(result.getService()).isNull();
            assertThat(result.getSource()).isNull();
        }

        @Test
        @DisplayName("空白 JSON 字符串 → 返回空的 CalcConfig")
        void blankJson() {
            CalcConfig result = CalcConfig.fromJson("   ");
            assertThat(result).isNotNull();
            assertThat(result.getService()).isNull();
        }

        @Test
        @DisplayName("空字符串 JSON → 返回空的 CalcConfig")
        void emptyJson() {
            CalcConfig result = CalcConfig.fromJson("");
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("无效 JSON → 抛出 IllegalArgumentException")
        void invalidJson() {
            assertThatThrownBy(() -> CalcConfig.fromJson("{invalid json}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("解析 calc_config 失败");
        }

        @Test
        @DisplayName("完整 EXTERNAL_API 配置 JSON → 正确解析所有字段")
        void externalApiConfig() {
            String json = "{\n"
                    + "    \"service\": {\n"
                    + "        \"discovery_type\": \"NACOS\",\n"
                    + "        \"service_name\": \"risk-service\",\n"
                    + "        \"namespace\": \"prod\",\n"
                    + "        \"group\": \"DEFAULT\",\n"
                    + "        \"path\": \"/api/v1/risk\",\n"
                    + "        \"method\": \"POST\",\n"
                    + "        \"timeout_ms\": 5000,\n"
                    + "        \"headers\": {\"Authorization\": \"Bearer xxx\"}\n"
                    + "    },\n"
                    + "    \"input_script_id\": \"script-input-001\",\n"
                    + "    \"output_script_id\": \"script-output-001\"\n"
                    + "}";

            CalcConfig result = CalcConfig.fromJson(json);

            assertThat(result.getService()).isNotNull();
            assertThat(result.getService().getDiscoveryType()).isEqualTo("NACOS");
            assertThat(result.getService().getServiceName()).isEqualTo("risk-service");
            assertThat(result.getService().getNamespace()).isEqualTo("prod");
            assertThat(result.getService().getPath()).isEqualTo("/api/v1/risk");
            assertThat(result.getService().getMethod()).isEqualTo("POST");
            assertThat(result.getService().getTimeoutMs()).isEqualTo(5000);
            assertThat(result.getService().getHeaders()).containsEntry("Authorization", "Bearer xxx");
            assertThat(result.getInputScriptId()).isEqualTo("script-input-001");
            assertThat(result.getOutputScriptId()).isEqualTo("script-output-001");
        }

        @Test
        @DisplayName("PARAM_MAPPING 配置 JSON（source）→ 正确解析")
        void paramMappingConfig() {
            String json = "{\"source\": \"insured.age\"}";

            CalcConfig result = CalcConfig.fromJson(json);

            assertThat(result.getSource()).isEqualTo("insured.age");
            assertThat(result.getService()).isNull();
        }

        @Test
        @DisplayName("空 JSON 对象 → 所有字段为 null")
        void emptyJsonObject() {
            CalcConfig result = CalcConfig.fromJson("{}");

            assertThat(result.getService()).isNull();
            assertThat(result.getInputScriptId()).isNull();
            assertThat(result.getOutputScriptId()).isNull();
            assertThat(result.getSource()).isNull();
        }
    }

    @Nested
    @DisplayName("toJson 序列化")
    class Serialization {

        @Test
        @DisplayName("包含 ServiceConfig → 序列化为 snake_case JSON")
        void withServiceConfig() {
            CalcConfig config = new CalcConfig();
            ServiceConfig service = new ServiceConfig();
            service.setServiceName("test-service");
            service.setMethod("POST");
            config.setService(service);
            config.setInputScriptId("in-1");
            config.setOutputScriptId("out-1");

            String json = config.toJson();

            assertThat(json).contains("\"service_name\"");
            assertThat(json).contains("\"test-service\"");
            assertThat(json).contains("\"input_script_id\"");
            assertThat(json).contains("\"in-1\"");
            assertThat(json).contains("\"output_script_id\"");
            assertThat(json).contains("\"out-1\"");
        }

        @Test
        @DisplayName("空 CalcConfig → 序列化为 JSON（所有字段为 null）")
        void emptyConfig() {
            CalcConfig config = new CalcConfig();
            String json = config.toJson();

            assertThat(json).contains("\"service\"");
            assertThat(json).contains("\"source\"");
            // Jackson default serializes null values
            CalcConfig restored = CalcConfig.fromJson(json);
            assertThat(restored.getService()).isNull();
            assertThat(restored.getSource()).isNull();
        }

        @Test
        @DisplayName("序列化→反序列化 往返一致")
        void roundTrip() {
            CalcConfig original = new CalcConfig();
            ServiceConfig service = new ServiceConfig();
            service.setDiscoveryType("STATIC");
            service.setServiceName("test-svc");
            service.setPath("/api/test");
            service.setMethod("GET");
            service.setTimeoutMs(3000);
            original.setService(service);
            original.setInputScriptId("input-1");
            original.setOutputScriptId("output-1");

            String json = original.toJson();
            CalcConfig restored = CalcConfig.fromJson(json);

            assertThat(restored.getService().getDiscoveryType()).isEqualTo("STATIC");
            assertThat(restored.getService().getServiceName()).isEqualTo("test-svc");
            assertThat(restored.getInputScriptId()).isEqualTo("input-1");
            assertThat(restored.getOutputScriptId()).isEqualTo("output-1");
        }
    }
}
