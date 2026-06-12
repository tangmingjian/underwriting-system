package com.insurance.uw.engine.core.rule.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.engine.core.model.entity.CrossDecisionTable;
import com.insurance.uw.engine.core.repository.CrossDecisionTableRepository;

import java.util.*;

/**
 * 交叉决策表评估器 — 按行列特征值查表得到布尔结果
 */
public class CrossDecisionTableEvaluator implements RuleEvaluator {

    private final CrossDecisionTableRepository repository;
    private final ObjectMapper objectMapper;

    public CrossDecisionTableEvaluator(CrossDecisionTableRepository repository,
                                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean evaluate(Map<String, Object> features, String config) {
        Map<String, Object> configMap = parseJson(config);
        String tableCode = (String) configMap.get("cross_table_id");
        if (tableCode == null) {
            return false;
        }

        Optional<CrossDecisionTable> tableOpt = repository.findByTableCode(tableCode);
        if (tableOpt.isEmpty()) {
            return false;
        }
        CrossDecisionTable table = tableOpt.get();
        if (!table.isEnabled()) {
            return false;
        }

        Object rowVal = resolveFeatureValue(features, table.getRowFeature());
        Object colVal = resolveFeatureValue(features, table.getColFeature());

        String rowKey = rowVal != null ? rowVal.toString() : "";
        String colKey = colVal != null ? colVal.toString() : "";

        List<Map<String, Object>> cells = parseCells(table.getCells());
        if (cells == null) {
            return table.getDefaultResult() != null && table.getDefaultResult();
        }

        for (Map<String, Object> cell : cells) {
            String cellRow = Objects.toString(cell.get("row"), "");
            String cellCol = Objects.toString(cell.get("col"), "");
            if (matchesCell(rowKey, cellRow) && matchesCell(colKey, cellCol)) {
                Object result = cell.get("result");
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
                return false;
            }
        }

        return table.getDefaultResult() != null && table.getDefaultResult();
    }

    private boolean matchesCell(String actualValue, String cellPattern) {
        if ("*".equals(cellPattern)) {
            return true;
        }
        return cellPattern.equals(actualValue);
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

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> parseCells(String cellsJson) {
        if (cellsJson == null || cellsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(cellsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
