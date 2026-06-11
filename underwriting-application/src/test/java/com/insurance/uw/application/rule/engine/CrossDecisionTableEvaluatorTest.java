package com.insurance.uw.application.rule.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.engine.core.model.entity.CrossDecisionTable;
import com.insurance.uw.engine.core.repository.CrossDecisionTableRepository;
import com.insurance.uw.engine.core.rule.engine.CrossDecisionTableEvaluator;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("CrossDecisionTableEvaluator - 交叉决策表评估器")
@ExtendWith(MockitoExtension.class)
class CrossDecisionTableEvaluatorTest {

    @Mock
    private CrossDecisionTableRepository repository;

    private CrossDecisionTableEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        evaluator = new CrossDecisionTableEvaluator(repository, objectMapper);
    }

    private CrossDecisionTable createTable() {
        CrossDecisionTable table = new CrossDecisionTable();
        table.setTableCode("CDT_001");
        table.setTableName("Test Table");
        table.setRowFeature("riskLevel");
        table.setColFeature("occupation");
        table.setCells("[{\"row\":\"HIGH\",\"col\":\"STUDENT\",\"result\":false},"
                + "{\"row\":\"HIGH\",\"col\":\"*\",\"result\":true},"
                + "{\"row\":\"LOW\",\"col\":\"*\",\"result\":true}]");
        table.setDefaultResult(false);
        table.setStatus(1);
        return table;
    }

    @Nested
    @DisplayName("精确匹配")
    class ExactMatch {

        @Test
        @DisplayName("行列精确命中 → cell result")
        void exactHit() {
            CrossDecisionTable table = createTable();
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "HIGH", "occupation", "STUDENT");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isFalse();
        }

        @Test
        @DisplayName("行命中，列通配 → 命中通配规则")
        void rowHitColWildcard() {
            CrossDecisionTable table = createTable();
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "HIGH", "occupation", "ENGINEER");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isTrue();
        }

        @Test
        @DisplayName("行命中，列通配 → 返回通配 result")
        void lowRowAlwaysTrue() {
            CrossDecisionTable table = createTable();
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "LOW", "occupation", "ANYTHING");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isTrue();
        }
    }

    @Nested
    @DisplayName("默认结果")
    class DefaultResult {

        @Test
        @DisplayName("行列都不匹配 → 返回 defaultResult")
        void noMatch() {
            CrossDecisionTable table = createTable();
            table.setDefaultResult(true);
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "MEDIUM", "occupation", "STUDENT");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isTrue();
        }

        @Test
        @DisplayName("defaultResult 为 false")
        void defaultFalse() {
            CrossDecisionTable table = createTable();
            table.setDefaultResult(false);
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "UNKNOWN", "occupation", "UNKNOWN");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isFalse();
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("决策表不存在 → false")
        void tableNotFound() {
            when(repository.findByTableCode("CDT_MISSING")).thenReturn(Optional.empty());

            Map<String, Object> features = Map.of("x", "y");
            String config = "{\"cross_table_id\":\"CDT_MISSING\"}";

            assertThat(evaluator.evaluate(features, config)).isFalse();
        }

        @Test
        @DisplayName("决策表未启用 → false")
        void tableDisabled() {
            CrossDecisionTable table = createTable();
            table.setStatus(0);
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "HIGH", "occupation", "STUDENT");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isFalse();
        }

        @Test
        @DisplayName("缺少 cross_table_id → false")
        void missingTableId() {
            assertThat(evaluator.evaluate(Map.of(), "{}")).isFalse();
        }

        @Test
        @DisplayName("cells 为 null → 返回 defaultResult")
        void nullCells() {
            CrossDecisionTable table = createTable();
            table.setCells(null);
            table.setDefaultResult(true);
            when(repository.findByTableCode("CDT_001")).thenReturn(Optional.of(table));

            Map<String, Object> features = Map.of("riskLevel", "HIGH", "occupation", "STUDENT");
            String config = "{\"cross_table_id\":\"CDT_001\"}";

            assertThat(evaluator.evaluate(features, config)).isTrue();
        }
    }
}
