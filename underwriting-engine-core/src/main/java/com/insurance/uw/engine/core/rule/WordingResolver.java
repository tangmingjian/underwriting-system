package com.insurance.uw.engine.core.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话素解析器 — 解析规则 wordingConfig JSON，按端+场景选择模板并替换 {{macro}} 宏变量
 */
public class WordingResolver {

    private static final Pattern MACRO_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    private final ObjectMapper objectMapper;

    public WordingResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, String> resolve(String wordingConfigJson,
                                       Map<String, Object> features,
                                       boolean passed) {
        if (wordingConfigJson == null || wordingConfigJson.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, String>> config;
        try {
            config = objectMapper.readValue(wordingConfigJson,
                    new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }

        if (config == null || config.isEmpty()) {
            return Collections.emptyMap();
        }

        String scenario = passed ? "pass" : "fail";
        Map<String, String> result = new LinkedHashMap<>();

        for (String side : new String[]{"A", "B", "C"}) {
            Map<String, String> sideConfig = config.get(side);
            if (sideConfig != null) {
                String template = sideConfig.get(scenario);
                if (template != null) {
                    result.put(side, replaceMacros(template, features));
                }
            }
        }

        return result;
    }

    String replaceMacros(String template, Map<String, Object> features) {
        if (template == null || features == null || features.isEmpty()) {
            return template != null ? template : "";
        }
        Matcher matcher = MACRO_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String macroPath = matcher.group(1).trim();
            Object value = resolveFeatureValue(features, macroPath);
            String replacement = value != null ? Matcher.quoteReplacement(String.valueOf(value)) : "";
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object resolveFeatureValue(Map<String, Object> features, String path) {
        if (path == null || features == null) {
            return null;
        }
        String[] parts = path.split("\\.");
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
}
