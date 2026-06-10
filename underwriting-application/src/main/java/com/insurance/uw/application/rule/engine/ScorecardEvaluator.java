package com.insurance.uw.application.rule.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.domain.model.entity.ScorecardConfig;
import com.insurance.uw.domain.repository.ScorecardConfigRepository;

import java.util.*;

/**
 * 评分卡评估器 — 按维度评分、代入公式、分桶得出布尔结果
 */
public class ScorecardEvaluator implements RuleEvaluator {

    private final ScorecardConfigRepository repository;
    private final ObjectMapper objectMapper;

    public ScorecardEvaluator(ScorecardConfigRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean evaluate(Map<String, Object> features, String config) {
        Map<String, Object> configMap = parseJson(config);
        String scorecardCode = (String) configMap.get("scorecard_id");
        if (scorecardCode == null) {
            return false;
        }

        Optional<ScorecardConfig> configOpt = repository.findByScorecardCode(scorecardCode);
        if (configOpt.isEmpty()) {
            return false;
        }
        ScorecardConfig sc = configOpt.get();
        if (!sc.isEnabled()) {
            return false;
        }

        List<Map<String, Object>> dimensions = parseJsonArray(sc.getDimensions());
        if (dimensions == null) {
            return false;
        }

        Map<String, Double> dimensionScores = new HashMap<>();
        for (Map<String, Object> dim : dimensions) {
            String dimName = (String) dim.get("name");
            String dimFeature = (String) dim.get("feature");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ranges = (List<Map<String, Object>>) dim.get("ranges");
            if (ranges == null) continue;

            Object featureValue = resolveFeatureValue(features, dimFeature);
            double score = scoreDimension(featureValue, ranges);
            dimensionScores.put(dimName, score);
        }

        String formula = sc.getScoringFormula();
        double totalScore = evaluateFormula(formula, dimensionScores);

        return matchBucket(totalScore, sc.getBuckets());
    }

    private double scoreDimension(Object featureValue, List<Map<String, Object>> ranges) {
        if (featureValue == null) {
            return 0;
        }
        for (Map<String, Object> range : ranges) {
            if (matchRange(featureValue, range)) {
                Object scoreObj = range.get("score");
                if (scoreObj instanceof Number) {
                    return ((Number) scoreObj).doubleValue();
                }
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private boolean matchRange(Object featureValue, Map<String, Object> range) {
        String operator = (String) range.get("operator");
        Object rangeValue = range.get("value");
        Object rangeMin = range.get("min");
        Object rangeMax = range.get("max");

        if (operator == null) {
            return false;
        }

        switch (operator) {
            case "EQ":
                return Objects.equals(toComparable(featureValue), toComparable(rangeValue));
            case "NEQ":
                return !Objects.equals(toComparable(featureValue), toComparable(rangeValue));
            case "GT":
                return compareNumbers(featureValue, rangeValue) > 0;
            case "GTE":
                return compareNumbers(featureValue, rangeValue) >= 0;
            case "LT":
                return compareNumbers(featureValue, rangeValue) < 0;
            case "LTE":
                return compareNumbers(featureValue, rangeValue) <= 0;
            case "BETWEEN":
                return compareNumbers(featureValue, rangeMin) >= 0
                        && compareNumbers(featureValue, rangeMax) <= 0;
            case "IN":
                if (rangeValue instanceof List) {
                    return ((List<?>) rangeValue).stream()
                            .anyMatch(v -> Objects.equals(toComparable(featureValue), toComparable(v)));
                }
                return false;
            case "IS_NULL":
                return featureValue == null;
            case "IS_NOT_NULL":
                return featureValue != null;
            default:
                return false;
        }
    }

    private double evaluateFormula(String formula, Map<String, Double> scores) {
        if (formula == null || formula.isBlank()) {
            return scores.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        String expr = formula;
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            expr = expr.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        try {
            return new ScriptEvaluator().evaluate(expr);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean matchBucket(double totalScore, String bucketsJson) {
        List<Map<String, Object>> buckets = parseJsonArray(bucketsJson);
        if (buckets == null) {
            return false;
        }
        for (Map<String, Object> bucket : buckets) {
            Object minObj = bucket.get("min");
            Object maxObj = bucket.get("max");
            double min = minObj instanceof Number ? ((Number) minObj).doubleValue() : Double.NEGATIVE_INFINITY;
            double max = maxObj instanceof Number ? ((Number) maxObj).doubleValue() : Double.POSITIVE_INFINITY;
            if (totalScore >= min && totalScore <= max) {
                Object result = bucket.get("result");
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            }
        }
        return false;
    }

    private Object resolveFeatureValue(Map<String, Object> features, String feature) {
        if (feature == null || features == null) {
            return null;
        }
        String[] parts = feature.split("\\.");
        Object current = features;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private int compareNumbers(Object featureValue, Object configValue) {
        if (featureValue instanceof Number && configValue instanceof Number) {
            double fv = ((Number) featureValue).doubleValue();
            double cv = ((Number) configValue).doubleValue();
            return Double.compare(fv, cv);
        }
        if (featureValue instanceof Comparable && configValue instanceof Comparable) {
            try {
                return ((Comparable) featureValue).compareTo(configValue);
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    private Object toComparable(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return value;
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 简单算术表达式计算器（仅支持 + - * / 和括号）
     */
    private static class ScriptEvaluator {
        private String expr;
        private int pos;

        double evaluate(String expression) {
            this.expr = expression.replaceAll("\\s+", "");
            this.pos = 0;
            double result = parseExpression();
            if (pos < expr.length()) {
                throw new IllegalArgumentException("Unexpected token at position " + pos);
            }
            return result;
        }

        private double parseExpression() {
            double left = parseTerm();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '+') { pos++; left += parseTerm(); }
                else if (op == '-') { pos++; left -= parseTerm(); }
                else break;
            }
            return left;
        }

        private double parseTerm() {
            double left = parseFactor();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '*') { pos++; left *= parseFactor(); }
                else if (op == '/') { pos++; left /= parseFactor(); }
                else break;
            }
            return left;
        }

        private double parseFactor() {
            if (pos >= expr.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            char ch = expr.charAt(pos);
            if (ch == '(') {
                pos++;
                double val = parseExpression();
                if (pos < expr.length() && expr.charAt(pos) == ')') {
                    pos++;
                }
                return val;
            }
            if (ch == '-' || Character.isDigit(ch)) {
                return parseNumber();
            }
            throw new IllegalArgumentException("Unexpected character: " + ch);
        }

        private double parseNumber() {
            int start = pos;
            if (pos < expr.length() && expr.charAt(pos) == '-') {
                pos++;
            }
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                pos++;
            }
            return Double.parseDouble(expr.substring(start, pos));
        }
    }
}
