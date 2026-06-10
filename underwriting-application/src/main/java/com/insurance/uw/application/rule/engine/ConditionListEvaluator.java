package com.insurance.uw.application.rule.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 条件列表评估器 — 支持 AND/OR 嵌套逻辑和 12 种比较运算符
 */
public class ConditionListEvaluator implements RuleEvaluator {

    private final ObjectMapper objectMapper;

    public ConditionListEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean evaluate(Map<String, Object> features, String config) {
        Map<String, Object> root = parseJson(config);
        if (root == null) {
            return false;
        }
        String logic = (String) root.getOrDefault("logic", "AND");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) root.get("items");
        if (items == null || items.isEmpty()) {
            return "AND".equals(logic);
        }
        if ("AND".equals(logic)) {
            return items.stream().allMatch(item -> evaluateItem(features, item));
        } else {
            return items.stream().anyMatch(item -> evaluateItem(features, item));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateItem(Map<String, Object> features, Map<String, Object> item) {
        String type = (String) item.get("type");
        if ("feature_group".equals(type)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groupItems = (List<Map<String, Object>>) item.get("items");
            String logic = (String) item.getOrDefault("logic", "AND");
            if (groupItems == null || groupItems.isEmpty()) {
                return "AND".equals(logic);
            }
            if ("AND".equals(logic)) {
                return groupItems.stream().allMatch(sub -> evaluateItem(features, sub));
            } else {
                return groupItems.stream().anyMatch(sub -> evaluateItem(features, sub));
            }
        }

        String feature = (String) item.get("feature");
        String operator = (String) item.get("operator");
        Object value = item.get("value");

        Object featureValue = resolveFeatureValue(features, feature);

        return compare(featureValue, operator, value);
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
    private boolean compare(Object featureValue, String operator, Object configValue) {
        if (operator == null) {
            return false;
        }
        switch (operator) {
            case "IS_NULL":
                return featureValue == null;
            case "IS_NOT_NULL":
                return featureValue != null;
        }

        if (featureValue == null) {
            return false;
        }

        switch (operator) {
            case "EQ":
                return Objects.equals(toComparable(featureValue), toComparable(configValue));
            case "NEQ":
                return !Objects.equals(toComparable(featureValue), toComparable(configValue));
            case "GT":
                return compareNumbers(featureValue, configValue) > 0;
            case "GTE":
                return compareNumbers(featureValue, configValue) >= 0;
            case "LT":
                return compareNumbers(featureValue, configValue) < 0;
            case "LTE":
                return compareNumbers(featureValue, configValue) <= 0;
            case "BETWEEN":
                if (configValue instanceof List && ((List<?>) configValue).size() == 2) {
                    List<?> range = (List<?>) configValue;
                    return compareNumbers(featureValue, range.get(0)) >= 0
                            && compareNumbers(featureValue, range.get(1)) <= 0;
                }
                return false;
            case "IN":
                if (configValue instanceof List) {
                    return ((List<?>) configValue).stream()
                            .anyMatch(v -> Objects.equals(toComparable(featureValue), toComparable(v)));
                }
                return false;
            case "NOT_IN":
                if (configValue instanceof List) {
                    return ((List<?>) configValue).stream()
                            .noneMatch(v -> Objects.equals(toComparable(featureValue), toComparable(v)));
                }
                return true;
            case "CONTAINS":
                if (featureValue instanceof String && configValue instanceof String) {
                    return ((String) featureValue).contains((String) configValue);
                }
                if (featureValue instanceof List) {
                    return ((List<?>) featureValue).stream()
                            .anyMatch(v -> Objects.equals(toComparable(v), toComparable(configValue)));
                }
                return false;
            default:
                return false;
        }
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
            return null;
        }
    }
}
