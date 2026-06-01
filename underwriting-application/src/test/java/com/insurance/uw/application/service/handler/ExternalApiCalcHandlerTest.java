package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.GroovyMappingEngine;
import com.insurance.uw.feature.core.handler.ExternalApiCalcHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExternalApiCalcHandler - 外部 API 调用处理器")
@ExtendWith(MockitoExtension.class)
class ExternalApiCalcHandlerTest {

    @Mock
    private FeatureScriptRepository scriptRepository;

    @Mock
    private GroovyMappingEngine groovyEngine;

    @Mock
    private DownstreamApiClient apiClient;

    private ExternalApiCalcHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExternalApiCalcHandler(scriptRepository, groovyEngine, apiClient);
    }

    private FeatureConfig createFeatureConfig() {
        FeatureConfig fc = new FeatureConfig();
        fc.setFeatureCode("RISK_SCORE");

        CalcConfig calcConfig = new CalcConfig();
        ServiceConfig service = new ServiceConfig();
        service.setServiceName("risk-service");
        service.setMethod("POST");
        calcConfig.setService(service);
        calcConfig.setInputScriptId("script-input-001");
        calcConfig.setOutputScriptId("script-output-001");
        fc.setCalcConfig(calcConfig);

        return fc;
    }

    @Nested
    @DisplayName("getSupportedType")
    class SupportedType {

        @Test
        @DisplayName("返回 EXTERNAL_API")
        void returnsExternalApi() {
            assertThat(handler.getSupportedType()).isEqualTo(CalcType.EXTERNAL_API);
        }
    }

    @Nested
    @DisplayName("成功调用流程")
    class SuccessfulExecution {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("完整 5 步流水线 → 正确调用并展平结果")
        void fullPipeline() {
            FeatureConfig fc = createFeatureConfig();
            Object ctx = new Object();

            FeatureScript inScript = new FeatureScript();
            inScript.setScriptId("script-input-001");
            inScript.setScriptText("def buildRequest(ctx) { return [:] }");

            FeatureScript outScript = new FeatureScript();
            outScript.setScriptId("script-output-001");
            outScript.setScriptText("def extractFeatures(resp, ctx) { return [:] }");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(inScript));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(outScript));

            Map<String, Object> request = Map.of("userId", "123");
            when(groovyEngine.invoke("script-input-001", inScript.getScriptText(), "buildRequest", ctx))
                    .thenReturn(request);

            Map<String, Object> response = Map.of("score", 85);
            when(apiClient.call(any(ServiceConfig.class), eq(request)))
                    .thenReturn(response);

            Map<String, Map<String, Object>> extracted = Map.of(
                    "INS001", Map.of("riskScore", 85, "fraudScore", 60)
            );
            when(groovyEngine.invoke("script-output-001", outScript.getScriptText(), "extractFeatures", response, ctx))
                    .thenReturn(extracted);

            Map<String, Object> result = handler.execute(ctx, fc);

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("INS001");
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapped = (Map<String, Object>) result.get("INS001");
            assertThat(wrapped).containsKey("RISK_SCORE");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureData = (Map<String, Object>) wrapped.get("RISK_SCORE");
            assertThat(featureData).containsEntry("riskScore", 85);
            assertThat(featureData).containsEntry("fraudScore", 60);

            verify(apiClient).call(any(ServiceConfig.class), eq(request));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("featureResults 为 null → 返回空 Map")
        void nullFeatureResults() {
            FeatureConfig fc = createFeatureConfig();
            Object ctx = new Object();

            FeatureScript inScript = new FeatureScript();
            inScript.setScriptId("script-input-001");
            inScript.setScriptText("...");

            FeatureScript outScript = new FeatureScript();
            outScript.setScriptId("script-output-001");
            outScript.setScriptText("...");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(inScript));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(outScript));

            when(groovyEngine.invoke(eq("script-input-001"), any(), eq("buildRequest"), any()))
                    .thenReturn(Map.of());
            when(apiClient.call(any(), any())).thenReturn(Map.of());
            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), any(), any()))
                    .thenReturn(null);

            Map<String, Object> result = handler.execute(ctx, fc);

            assertThat(result).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("多被保人结果 → 正确展平")
        void multipleInsuredResults() {
            FeatureConfig fc = createFeatureConfig();
            Object ctx = new Object();

            FeatureScript inScript = new FeatureScript();
            inScript.setScriptId("script-input-001");
            inScript.setScriptText("...");

            FeatureScript outScript = new FeatureScript();
            outScript.setScriptId("script-output-001");
            outScript.setScriptText("...");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(inScript));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(outScript));
            when(groovyEngine.invoke(eq("script-input-001"), any(), eq("buildRequest"), any()))
                    .thenReturn(Map.of());
            when(apiClient.call(any(), any())).thenReturn(Map.of());

            Map<String, Map<String, Object>> extracted = Map.of(
                    "INS001", Map.of("riskScore", 85, "fraudScore", 60),
                    "INS002", Map.of("riskScore", 60, "fraudScore", 80)
            );
            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), any(), any()))
                    .thenReturn(extracted);

            Map<String, Object> result = handler.execute(ctx, fc);

            assertThat(result).hasSize(2);
            assertThat(result).containsKeys("INS001", "INS002");
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapped1 = (Map<String, Object>) result.get("INS001");
            assertThat(wrapped1).containsKey("RISK_SCORE");
            @SuppressWarnings("unchecked")
            Map<String, Object> data1 = (Map<String, Object>) wrapped1.get("RISK_SCORE");
            assertThat(data1).containsEntry("riskScore", 85);
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapped2 = (Map<String, Object>) result.get("INS002");
            assertThat(wrapped2).containsKey("RISK_SCORE");
            @SuppressWarnings("unchecked")
            Map<String, Object> data2 = (Map<String, Object>) wrapped2.get("RISK_SCORE");
            assertThat(data2).containsEntry("riskScore", 60);
        }
    }

    @Nested
    @DisplayName("异常场景")
    class ExceptionScenarios {

        @Test
        @DisplayName("service 未配置 → 抛出 IllegalArgumentException")
        void missingServiceConfig() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("TEST_FC");
            CalcConfig calcConfig = new CalcConfig();
            fc.setCalcConfig(calcConfig);

            assertThatThrownBy(() -> handler.execute(new Object(), fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("calc_config.service 未配置");
        }

        @Test
        @DisplayName("入参脚本不存在 → 抛出 IllegalArgumentException")
        void missingInputScript() {
            FeatureConfig fc = createFeatureConfig();
            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.execute(new Object(), fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("入参脚本不存在");
        }

        @Test
        @DisplayName("出参脚本不存在 → 抛出 IllegalArgumentException")
        void missingOutputScript() {
            FeatureConfig fc = createFeatureConfig();

            FeatureScript inScript = new FeatureScript();
            inScript.setScriptId("script-input-001");
            inScript.setScriptText("...");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(inScript));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.execute(new Object(), fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("出参脚本不存在");
        }
    }
}
