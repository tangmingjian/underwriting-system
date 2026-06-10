package com.insurance.uw.application.rule.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConditionListEvaluator - 条件列表评估器")
class ConditionListEvaluatorTest {

    private ConditionListEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionListEvaluator(new ObjectMapper());
    }

    @Nested
    @DisplayName("AND 逻辑")
    class AndLogic {

        @Test
        @DisplayName("所有条件满足 → true")
        void allMatch() {
            Map<String, Object> features = Map.of("age", 25, "score", 80);
            String config = "{\"logic\":\"AND\",\"items\":["
                    + "{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":18},"
                    + "{\"feature\":\"score\",\"operator\":\"GTE\",\"value\":60}"
                    + "]}";
            assertThat(evaluator.evaluate(features, config)).isTrue();
        }

        @Test
        @DisplayName("一个条件不满足 → false")
        void oneFails() {
            Map<String, Object> features = Map.of("age", 25, "score", 50);
            String config = "{\"logic\":\"AND\",\"items\":["
                    + "{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":18},"
                    + "{\"feature\":\"score\",\"operator\":\"GTE\",\"value\":60}"
                    + "]}";
            assertThat(evaluator.evaluate(features, config)).isFalse();
        }
    }

    @Nested
    @DisplayName("OR 逻辑")
    class OrLogic {

        @Test
        @DisplayName("任一条件满足 → true")
        void anyMatch() {
            Map<String, Object> features = Map.of("age", 16, "score", 80);
            String config = "{\"logic\":\"OR\",\"items\":["
                    + "{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":18},"
                    + "{\"feature\":\"score\",\"operator\":\"GTE\",\"value\":60}"
                    + "]}";
            assertThat(evaluator.evaluate(features, config)).isTrue();
        }

        @Test
        @DisplayName("都不满足 → false")
        void noneMatch() {
            Map<String, Object> features = Map.of("age", 16, "score", 50);
            String config = "{\"logic\":\"OR\",\"items\":["
                    + "{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":18},"
                    + "{\"feature\":\"score\",\"operator\":\"GTE\",\"value\":60}"
                    + "]}";
            assertThat(evaluator.evaluate(features, config)).isFalse();
        }
    }

    @Nested
    @DisplayName("嵌套 feature_group")
    class NestedGroup {

        @Test
        @DisplayName("嵌套 AND group 包含在 OR 中")
        void nestedAndInOr() {
            Map<String, Object> features = Map.of("age", 25, "score", 80);
            String config = "{\"logic\":\"OR\",\"items\":["
                    + "{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":18},"
                    + "{\"type\":\"feature_group\",\"logic\":\"AND\",\"items\":["
                    + "  {\"feature\":\"score\",\"operator\":\"GTE\",\"value\":60},"
                    + "  {\"feature\":\"score\",\"operator\":\"LTE\",\"value\":100}"
                    + "]}"
                    + "]}";
            assertThat(evaluator.evaluate(features, config)).isTrue();
        }
    }

    @Nested
    @DisplayName("运算符测试")
    class Operators {

        @Test
        @DisplayName("EQ - 相等")
        void eq() {
            assertThat(eval(Map.of("val", "abc"), "EQ", "abc")).isTrue();
            assertThat(eval(Map.of("val", 10), "EQ", 10)).isTrue();
            assertThat(eval(Map.of("val", 10), "EQ", 20)).isFalse();
        }

        @Test
        @DisplayName("NEQ - 不等")
        void neq() {
            assertThat(eval(Map.of("val", "abc"), "NEQ", "xyz")).isTrue();
            assertThat(eval(Map.of("val", 10), "NEQ", 10)).isFalse();
        }

        @Test
        @DisplayName("GT - 大于")
        void gt() {
            assertThat(eval(Map.of("val", 30), "GT", 18)).isTrue();
            assertThat(eval(Map.of("val", 10), "GT", 18)).isFalse();
        }

        @Test
        @DisplayName("GTE - 大于等于")
        void gte() {
            assertThat(eval(Map.of("val", 18), "GTE", 18)).isTrue();
            assertThat(eval(Map.of("val", 17), "GTE", 18)).isFalse();
        }

        @Test
        @DisplayName("LT - 小于")
        void lt() {
            assertThat(eval(Map.of("val", 10), "LT", 18)).isTrue();
            assertThat(eval(Map.of("val", 20), "LT", 18)).isFalse();
        }

        @Test
        @DisplayName("LTE - 小于等于")
        void lte() {
            assertThat(eval(Map.of("val", 18), "LTE", 18)).isTrue();
            assertThat(eval(Map.of("val", 19), "LTE", 18)).isFalse();
        }

        @Test
        @DisplayName("BETWEEN - 区间")
        void between() {
            assertThat(eval(Map.of("val", 50), "BETWEEN", List.of(10, 100))).isTrue();
            assertThat(eval(Map.of("val", 5), "BETWEEN", List.of(10, 100))).isFalse();
            assertThat(eval(Map.of("val", 200), "BETWEEN", List.of(10, 100))).isFalse();
        }

        @Test
        @DisplayName("IN - 包含")
        void in() {
            assertThat(eval(Map.of("val", "A"), "IN", List.of("A", "B", "C"))).isTrue();
            assertThat(eval(Map.of("val", "D"), "IN", List.of("A", "B", "C"))).isFalse();
        }

        @Test
        @DisplayName("NOT_IN - 不包含")
        void notIn() {
            assertThat(eval(Map.of("val", "D"), "NOT_IN", List.of("A", "B", "C"))).isTrue();
            assertThat(eval(Map.of("val", "A"), "NOT_IN", List.of("A", "B", "C"))).isFalse();
        }

        @Test
        @DisplayName("IS_NULL - 为空")
        void isNull() {
            assertThat(eval(Map.of(), "IS_NULL", null)).isTrue();
            assertThat(eval(Map.of("val", "x"), "IS_NULL", null)).isFalse();
        }

        @Test
        @DisplayName("IS_NOT_NULL - 不为空")
        void isNotNull() {
            assertThat(eval(Map.of("val", "x"), "IS_NOT_NULL", null)).isTrue();
            assertThat(eval(Map.of(), "IS_NOT_NULL", null)).isFalse();
        }

        @Test
        @DisplayName("CONTAINS - 字符串包含")
        void contains() {
            assertThat(eval(Map.of("val", "hello world"), "CONTAINS", "world")).isTrue();
            assertThat(eval(Map.of("val", "hello"), "CONTAINS", "xyz")).isFalse();
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("空 items AND → true")
        void emptyItemsAnd() {
            assertThat(evaluator.evaluate(Map.of(), "{\"logic\":\"AND\",\"items\":[]}")).isTrue();
        }

        @Test
        @DisplayName("空 items OR → false")
        void emptyItemsOr() {
            assertThat(evaluator.evaluate(Map.of(), "{\"logic\":\"OR\",\"items\":[]}")).isFalse();
        }

        @Test
        @DisplayName("无效 JSON → false")
        void invalidJson() {
            assertThat(evaluator.evaluate(Map.of(), "{invalid}")).isFalse();
        }

        @Test
        @DisplayName("null features → 不报错")
        void nullFeatures() {
            String config = "{\"logic\":\"AND\",\"items\":[{\"feature\":\"x\",\"operator\":\"IS_NULL\"}]}";
            assertThat(evaluator.evaluate(null, config)).isTrue();
        }

        @Test
        @DisplayName("未知 operator → false")
        void unknownOperator() {
            assertThat(eval(Map.of("val", 10), "UNKNOWN_OP", 10)).isFalse();
        }

        @Test
        @DisplayName("嵌套特征路径解析")
        void nestedFeaturePath() {
            Map<String, Object> features = Map.of("ins",
                    Map.of("creditScore", Map.of("score", 600)));
            String config = "{\"logic\":\"AND\",\"items\":[{\"feature\":\"ins.creditScore.score\",\"operator\":\"GTE\",\"value\":600}]}";
            assertThat(evaluator.evaluate(features, config)).isTrue();
        }

        @Test
        @DisplayName("默认逻辑为 AND")
        void defaultLogic() {
            Map<String, Object> features = Map.of("age", 25);
            String config = "{\"items\":[{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":18}]}";
            assertThat(evaluator.evaluate(features, config)).isTrue();
        }
    }

    private boolean eval(Map<String, Object> features, String operator, Object value) {
        String valJson;
        if (value instanceof List) {
            valJson = ((List<?>) value).stream()
                    .map(v -> v instanceof String ? "\"" + v + "\"" : String.valueOf(v))
                    .reduce((a, b) -> a + "," + b).map(s -> "[" + s + "]").orElse("[]");
        } else if (value instanceof String) {
            valJson = "\"" + value + "\"";
        } else {
            valJson = String.valueOf(value);
        }
        String config = "{\"logic\":\"AND\",\"items\":[{\"feature\":\"val\",\"operator\":\"" + operator + "\",\"value\":" + valJson + "}]}";
        return evaluator.evaluate(features, config);
    }
}
