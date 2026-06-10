package com.insurance.uw.application.rule.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.domain.model.entity.ScorecardConfig;
import com.insurance.uw.domain.repository.ScorecardConfigRepository;
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
import static org.mockito.Mockito.when;

@DisplayName("ScorecardEvaluator - 评分卡评估器")
@ExtendWith(MockitoExtension.class)
class ScorecardEvaluatorTest {

    @Mock
    private ScorecardConfigRepository repository;

    private ScorecardEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        evaluator = new ScorecardEvaluator(repository, objectMapper);
    }

    private ScorecardConfig createConfig() {
        ScorecardConfig config = new ScorecardConfig();
        config.setScorecardCode("SC_001");
        config.setScorecardName("Test Scorecard");
        config.setDimensions("["
                + "{\"name\":\"ageScore\",\"feature\":\"age\",\"ranges\":["
                + "  {\"operator\":\"BETWEEN\",\"min\":18,\"max\":30,\"score\":80},"
                + "  {\"operator\":\"BETWEEN\",\"min\":31,\"max\":50,\"score\":60},"
                + "  {\"operator\":\"GTE\",\"value\":51,\"score\":40}"
                + "]},"
                + "{\"name\":\"incomeScore\",\"feature\":\"income\",\"ranges\":["
                + "  {\"operator\":\"GTE\",\"value\":10000,\"score\":90},"
                + "  {\"operator\":\"BETWEEN\",\"min\":5000,\"max\":9999,\"score\":50},"
                + "  {\"operator\":\"LT\",\"value\":5000,\"score\":20}"
                + "]}"
                + "]");
        config.setScoringFormula("{ageScore}*0.5+{incomeScore}*0.5");
        config.setBuckets("["
                + "{\"min\":0,\"max\":59,\"result\":false},"
                + "{\"min\":60,\"max\":100,\"result\":true}"
                + "]");
        config.setStatus(1);
        return config;
    }

    @Nested
    @DisplayName("评分计算")
    class Scoring {

        @Test
        @DisplayName("各项评分 → 代入公式 → 分桶 → true")
        void highScore() {
            ScorecardConfig config = createConfig();
            when(repository.findByScorecardCode("SC_001")).thenReturn(Optional.of(config));

            Map<String, Object> features = Map.of("age", 25, "income", 12000);
            String cfg = "{\"scorecard_id\":\"SC_001\"}";

            assertThat(evaluator.evaluate(features, cfg)).isTrue();
        }

        @Test
        @DisplayName("低评分 → 分桶 → false")
        void lowScore() {
            ScorecardConfig config = createConfig();
            when(repository.findByScorecardCode("SC_001")).thenReturn(Optional.of(config));

            Map<String, Object> features = Map.of("age", 60, "income", 3000);
            String cfg = "{\"scorecard_id\":\"SC_001\"}";

            assertThat(evaluator.evaluate(features, cfg)).isFalse();
        }

        @Test
        @DisplayName("恰好边界分 → 命中临界 bucket")
        void boundaryScore() {
            ScorecardConfig config = createConfig();
            when(repository.findByScorecardCode("SC_001")).thenReturn(Optional.of(config));

            Map<String, Object> features = Map.of("age", 80, "income", 5000);
            String cfg = "{\"scorecard_id\":\"SC_001\"}";

            assertThat(evaluator.evaluate(features, cfg)).isFalse();
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("评分卡不存在 → false")
        void configNotFound() {
            when(repository.findByScorecardCode("SC_MISSING")).thenReturn(Optional.empty());

            Map<String, Object> features = Map.of("x", 1);
            String cfg = "{\"scorecard_id\":\"SC_MISSING\"}";

            assertThat(evaluator.evaluate(features, cfg)).isFalse();
        }

        @Test
        @DisplayName("评分卡未启用 → false")
        void configDisabled() {
            ScorecardConfig config = createConfig();
            config.setStatus(0);
            when(repository.findByScorecardCode("SC_001")).thenReturn(Optional.of(config));

            Map<String, Object> features = Map.of("age", 25, "income", 12000);
            String cfg = "{\"scorecard_id\":\"SC_001\"}";

            assertThat(evaluator.evaluate(features, cfg)).isFalse();
        }

        @Test
        @DisplayName("缺少 scorecard_id → false")
        void missingScorecardId() {
            assertThat(evaluator.evaluate(Map.of(), "{}")).isFalse();
        }

        @Test
        @DisplayName("简单公式（仅加法）")
        void simpleFormula() {
            ScorecardConfig config = new ScorecardConfig();
            config.setScorecardCode("SC_SIMPLE");
            config.setScorecardName("Simple");
            config.setDimensions("["
                    + "{\"name\":\"a\",\"feature\":\"x\",\"ranges\":[{\"operator\":\"EQ\",\"value\":10,\"score\":50}]},"
                    + "{\"name\":\"b\",\"feature\":\"y\",\"ranges\":[{\"operator\":\"EQ\",\"value\":20,\"score\":30}]}"
                    + "]");
            config.setScoringFormula("{a}+{b}");
            config.setBuckets("[{\"min\":0,\"max\":70,\"result\":false},{\"min\":80,\"max\":100,\"result\":true}]");
            config.setStatus(1);
            when(repository.findByScorecardCode("SC_SIMPLE")).thenReturn(Optional.of(config));

            Map<String, Object> features = Map.of("x", 10, "y", 20);
            String cfg = "{\"scorecard_id\":\"SC_SIMPLE\"}";

            assertThat(evaluator.evaluate(features, cfg)).isTrue();
        }
    }
}
