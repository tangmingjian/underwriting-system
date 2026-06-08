package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.GroovyMappingEngine;
import com.insurance.uw.application.feature.handler.ExternalApiCalcHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

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

    @Nested
    @DisplayName("分批调用流程 (buildRequest 返回 List<Map>)")
    class BatchExecution {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("buildRequest 返回 List<Map> → 循环调用 API → extractFeatures 接收 List<Map>")
        void buildRequestReturnsList() {
            FeatureConfig fc = createFeatureConfig();
            Object ctx = new Object();

            FeatureScript inScript = new FeatureScript();
            inScript.setScriptId("script-input-001");
            inScript.setScriptText("def buildRequest(ctx) { return [] }");

            FeatureScript outScript = new FeatureScript();
            outScript.setScriptId("script-output-001");
            outScript.setScriptText("def extractFeatures(responses, ctx) { return [:] }");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(inScript));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(outScript));

            // buildRequest returns List<Map> with 3 batches
            List<Map<String, Object>> batchRequests = List.of(
                    Map.of("persons", List.of("P001", "P002")),
                    Map.of("persons", List.of("P003", "P004")),
                    Map.of("persons", List.of("P005"))
            );
            when(groovyEngine.invoke(eq("script-input-001"), any(), eq("buildRequest"), any()))
                    .thenReturn(batchRequests);

            // Each batch gets its own API call
            Map<String, Object> response1 = Map.of("scores", List.of(Map.of("id", "P001", "score", 80)));
            Map<String, Object> response2 = Map.of("scores", List.of(Map.of("id", "P003", "score", 90)));
            Map<String, Object> response3 = Map.of("scores", List.of(Map.of("id", "P005", "score", 70)));
            when(apiClient.call(any(ServiceConfig.class), eq(batchRequests.get(0))))
                    .thenReturn(response1);
            when(apiClient.call(any(ServiceConfig.class), eq(batchRequests.get(1))))
                    .thenReturn(response2);
            when(apiClient.call(any(ServiceConfig.class), eq(batchRequests.get(2))))
                    .thenReturn(response3);

            // extractFeatures receives List<Map> of all responses
            Map<String, Map<String, Object>> extracted = Map.of(
                    "INS001", Map.of("riskScore", 80),
                    "INS002", Map.of("riskScore", 90),
                    "INS003", Map.of("riskScore", 70)
            );
            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), any(List.class), any()))
                    .thenReturn(extracted);

            Map<String, Object> result = handler.execute(ctx, fc);

            assertThat(result).hasSize(3);
            assertThat(result).containsKeys("INS001", "INS002", "INS003");
            verify(apiClient, times(3)).call(any(ServiceConfig.class), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("空 List → 不调用 API → extractFeatures 接收空列表")
        void emptyBatchList() {
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
                    .thenReturn(List.of());

            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), any(List.class), any()))
                    .thenReturn(Map.of());

            Map<String, Object> result = handler.execute(ctx, fc);

            assertThat(result).isEmpty();
            verify(apiClient, times(0)).call(any(), any());
        }
    }

    @Nested
    @DisplayName("executeBatch 降级场景")
    class ExecuteBatchFallback {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("某特征 buildRequest 返回 List<Map> → 降级为单独执行")
        void batchFeatureFallsBackToIndividual() {
            // fc1: returns Map (mergeable)
            FeatureConfig fc1 = createFeatureConfig();

            // fc2: returns List<Map> (falls back)
            FeatureConfig fc2 = new FeatureConfig();
            fc2.setFeatureCode("CREDIT_SCORE");
            CalcConfig calcConfig2 = new CalcConfig();
            ServiceConfig service2 = new ServiceConfig();
            service2.setServiceName("credit-service");
            service2.setMethod("POST");
            calcConfig2.setService(service2);
            calcConfig2.setInputScriptId("script-input-002");
            calcConfig2.setOutputScriptId("script-output-002");
            fc2.setCalcConfig(calcConfig2);

            Object ctx = new Object();

            FeatureScript in1 = new FeatureScript();
            in1.setScriptId("script-input-001");
            in1.setScriptText("...");

            FeatureScript out1 = new FeatureScript();
            out1.setScriptId("script-output-001");
            out1.setScriptText("...");

            FeatureScript in2 = new FeatureScript();
            in2.setScriptId("script-input-002");
            in2.setScriptText("...");

            FeatureScript out2 = new FeatureScript();
            out2.setScriptId("script-output-002");
            out2.setScriptText("...");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(in1));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(out1));
            when(scriptRepository.findByScriptId("script-input-002"))
                    .thenReturn(Optional.of(in2));
            when(scriptRepository.findByScriptId("script-output-002"))
                    .thenReturn(Optional.of(out2));

            // fc1's buildRequest returns Map
            Map<String, Object> request1 = Map.of("userId", "123");
            when(groovyEngine.invoke(eq("script-input-001"), any(), eq("buildRequest"), any()))
                    .thenReturn(request1);

            // fc2's buildRequest returns List<Map> — triggers fallback
            List<Map<String, Object>> batchRequests = List.of(
                    Map.of("persons", List.of("P001")),
                    Map.of("persons", List.of("P002"))
            );
            when(groovyEngine.invoke(eq("script-input-002"), any(), eq("buildRequest"), any()))
                    .thenReturn(batchRequests);

            // fc1's merged API call
            Map<String, Object> mergedResponse = Map.of("score", 85);
            when(apiClient.call(any(ServiceConfig.class), eq(request1)))
                    .thenReturn(mergedResponse);

            // fc2's batch API calls (via execute() fallback)
            when(apiClient.call(any(ServiceConfig.class), eq(batchRequests.get(0))))
                    .thenReturn(Map.of("score", 60));
            when(apiClient.call(any(ServiceConfig.class), eq(batchRequests.get(1))))
                    .thenReturn(Map.of("score", 70));

            // fc1's extractFeatures
            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), eq(mergedResponse), any()))
                    .thenReturn(Map.of("INS001", Map.of("riskScore", 85)));

            // fc2's extractFeatures — receives List<Map>
            when(groovyEngine.invoke(eq("script-output-002"), any(), eq("extractFeatures"), any(List.class), any()))
                    .thenReturn(Map.of("INS001", Map.of("creditScore", 65)));

            Map<String, Map<String, Object>> result = handler.executeBatch(ctx, List.of(fc1, fc2));

            assertThat(result).hasSize(2);
            assertThat(result).containsKeys("RISK_SCORE", "CREDIT_SCORE");
            // 1 merged call for fc1 + 2 batch calls for fc2 = 3 total
            verify(apiClient, times(3)).call(any(ServiceConfig.class), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("所有特征都返回 Map → 走原 deepMerge 路径（不变）")
        void allMapFeaturesUsesMergePath() {
            FeatureConfig fc1 = createFeatureConfig();
            FeatureConfig fc2 = new FeatureConfig();
            fc2.setFeatureCode("CREDIT_SCORE");
            CalcConfig calcConfig2 = new CalcConfig();
            ServiceConfig service2 = new ServiceConfig();
            service2.setServiceName("risk-service");
            service2.setMethod("POST");
            calcConfig2.setService(service2);
            calcConfig2.setInputScriptId("script-input-002");
            calcConfig2.setOutputScriptId("script-output-002");
            fc2.setCalcConfig(calcConfig2);

            Object ctx = new Object();

            FeatureScript in1 = new FeatureScript();
            in1.setScriptId("script-input-001");
            in1.setScriptText("...");
            FeatureScript out1 = new FeatureScript();
            out1.setScriptId("script-output-001");
            out1.setScriptText("...");
            FeatureScript in2 = new FeatureScript();
            in2.setScriptId("script-input-002");
            in2.setScriptText("...");
            FeatureScript out2 = new FeatureScript();
            out2.setScriptId("script-output-002");
            out2.setScriptText("...");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(in1));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(out1));
            when(scriptRepository.findByScriptId("script-input-002"))
                    .thenReturn(Optional.of(in2));
            when(scriptRepository.findByScriptId("script-output-002"))
                    .thenReturn(Optional.of(out2));

            when(groovyEngine.invoke(eq("script-input-001"), any(), eq("buildRequest"), any()))
                    .thenReturn(Map.of("userId", "123"));
            when(groovyEngine.invoke(eq("script-input-002"), any(), eq("buildRequest"), any()))
                    .thenReturn(Map.of("userId", "456"));

            Map<String, Object> mergedResponse = Map.of("score", 85);
            when(apiClient.call(any(ServiceConfig.class), any(Map.class)))
                    .thenReturn(mergedResponse);

            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), any(), any()))
                    .thenReturn(Map.of("INS001", Map.of("riskScore", 85)));
            when(groovyEngine.invoke(eq("script-output-002"), any(), eq("extractFeatures"), any(), any()))
                    .thenReturn(Map.of("INS002", Map.of("creditScore", 90)));

            Map<String, Map<String, Object>> result = handler.executeBatch(ctx, List.of(fc1, fc2));

            assertThat(result).hasSize(2);
            assertThat(result).containsKeys("RISK_SCORE", "CREDIT_SCORE");
            // 1 merged call only
            verify(apiClient, times(1)).call(any(ServiceConfig.class), any(Map.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("所有特征都返回 List<Map> → 全部降级单独执行")
        void allBatchFeaturesFallBack() {
            FeatureConfig fc1 = createFeatureConfig();
            FeatureConfig fc2 = new FeatureConfig();
            fc2.setFeatureCode("CREDIT_SCORE");
            CalcConfig calcConfig2 = new CalcConfig();
            ServiceConfig service2 = new ServiceConfig();
            service2.setServiceName("credit-service");
            service2.setMethod("POST");
            calcConfig2.setService(service2);
            calcConfig2.setInputScriptId("script-input-002");
            calcConfig2.setOutputScriptId("script-output-002");
            fc2.setCalcConfig(calcConfig2);

            Object ctx = new Object();

            FeatureScript in1 = new FeatureScript();
            in1.setScriptId("script-input-001");
            in1.setScriptText("...");
            FeatureScript out1 = new FeatureScript();
            out1.setScriptId("script-output-001");
            out1.setScriptText("...");
            FeatureScript in2 = new FeatureScript();
            in2.setScriptId("script-input-002");
            in2.setScriptText("...");
            FeatureScript out2 = new FeatureScript();
            out2.setScriptId("script-output-002");
            out2.setScriptText("...");

            when(scriptRepository.findByScriptId("script-input-001"))
                    .thenReturn(Optional.of(in1));
            when(scriptRepository.findByScriptId("script-output-001"))
                    .thenReturn(Optional.of(out1));
            when(scriptRepository.findByScriptId("script-input-002"))
                    .thenReturn(Optional.of(in2));
            when(scriptRepository.findByScriptId("script-output-002"))
                    .thenReturn(Optional.of(out2));

            // Both return List<Map>
            when(groovyEngine.invoke(eq("script-input-001"), any(), eq("buildRequest"), any()))
                    .thenReturn(List.of(Map.of("persons", List.of("P001"))));
            when(groovyEngine.invoke(eq("script-input-002"), any(), eq("buildRequest"), any()))
                    .thenReturn(List.of(Map.of("persons", List.of("P002"))));

            when(apiClient.call(any(ServiceConfig.class), any(Map.class)))
                    .thenReturn(Map.of("score", 80));

            when(groovyEngine.invoke(eq("script-output-001"), any(), eq("extractFeatures"), any(List.class), any()))
                    .thenReturn(Map.of("INS001", Map.of("riskScore", 80)));
            when(groovyEngine.invoke(eq("script-output-002"), any(), eq("extractFeatures"), any(List.class), any()))
                    .thenReturn(Map.of("INS002", Map.of("creditScore", 90)));

            Map<String, Map<String, Object>> result = handler.executeBatch(ctx, List.of(fc1, fc2));

            assertThat(result).hasSize(2);
        }
    }
}
