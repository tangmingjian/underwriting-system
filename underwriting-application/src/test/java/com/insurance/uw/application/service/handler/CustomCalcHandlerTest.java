package com.insurance.uw.application.service.handler;

import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.engine.core.handler.CustomCalcHandler;
import com.insurance.uw.engine.core.handler.CustomFeatureHandler;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CustomCalcHandler - 自定义 Java 特征处理器")
class CustomCalcHandlerTest {

    private CustomCalcHandler handler;
    private CustomFeatureHandler riskScoreHandler;
    private CustomFeatureHandler creditScoreHandler;

    @BeforeEach
    void setUp() {
        riskScoreHandler = new CustomFeatureHandler() {
            @Override
            public String getFeatureCode() {
                return "RISK_SCORE";
            }

            @Override
            public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
                return Map.of("__ORDER__", Map.of("RISK_SCORE", 85));
            }
        };

        creditScoreHandler = new CustomFeatureHandler() {
            @Override
            public String getFeatureCode() {
                return "CREDIT_SCORE";
            }

            @Override
            public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
                return Map.of("INS001", Map.of("CREDIT_SCORE", 90));
            }
        };

        handler = new CustomCalcHandler(List.of(riskScoreHandler, creditScoreHandler));
    }

    @Nested
    @DisplayName("getSupportedType")
    class SupportedType {

        @Test
        @DisplayName("返回 CUSTOM")
        void returnsCustom() {
            assertThat(handler.getSupportedType()).isEqualTo(CalcType.CUSTOM);
        }
    }

    @Nested
    @DisplayName("execute - 单特征执行")
    class Execute {

        @Test
        @DisplayName("featureCode 匹配 → 委托给对应 Handler")
        void routesByFeatureCode() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("RISK_SCORE");

            Map<String, Object> result = handler.execute(new Object(), fc);

            assertThat(result).containsKey("__ORDER__");
        }

        @Test
        @DisplayName("featureCode 无匹配 → 抛出 IllegalArgumentException")
        void throwsWhenNoMatchingHandler() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("UNKNOWN_FEATURE");

            assertThatThrownBy(() -> handler.execute(new Object(), fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UNKNOWN_FEATURE");
        }

        @Test
        @DisplayName("不同 featureCode 路由到不同 Handler")
        void routesToCorrectHandler() {
            FeatureConfig fc1 = new FeatureConfig();
            fc1.setFeatureCode("RISK_SCORE");

            FeatureConfig fc2 = new FeatureConfig();
            fc2.setFeatureCode("CREDIT_SCORE");

            Map<String, Object> result1 = handler.execute(new Object(), fc1);
            Map<String, Object> result2 = handler.execute(new Object(), fc2);

            assertThat(result1).containsKey("__ORDER__");
            assertThat(result2).containsKey("INS001");
        }
    }

    @Nested
    @DisplayName("executeBatch - 批量执行")
    class ExecuteBatch {

        @Test
        @DisplayName("多个 CUSTOM 特征 → 逐个调用")
        void iteratesThroughEachFeature() {
            FeatureConfig fc1 = new FeatureConfig();
            fc1.setFeatureCode("RISK_SCORE");

            FeatureConfig fc2 = new FeatureConfig();
            fc2.setFeatureCode("CREDIT_SCORE");

            Map<String, Map<String, Object>> result = handler.executeBatch(new Object(), List.of(fc1, fc2));

            assertThat(result).hasSize(2);
            assertThat(result).containsKeys("RISK_SCORE", "CREDIT_SCORE");
            assertThat(result.get("RISK_SCORE")).containsKey("__ORDER__");
            assertThat(result.get("CREDIT_SCORE")).containsKey("INS001");
        }

        @Test
        @DisplayName("空列表 → 返回空 Map")
        void emptyListReturnsEmptyMap() {
            Map<String, Map<String, Object>> result = handler.executeBatch(new Object(), List.of());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("单个特征 → 返回单条结果")
        void singleFeatureReturnsOneEntry() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("RISK_SCORE");

            Map<String, Map<String, Object>> result = handler.executeBatch(new Object(), List.of(fc));

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("RISK_SCORE");
        }
    }
}
