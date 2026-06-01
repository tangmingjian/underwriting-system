package com.insurance.uw.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.service.DownstreamApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.net.URI;
import java.util.*;

/**
 * 下游 API Mock 实现 — 用于本地测试，不依赖真实下游服务。
 *
 * 匹配策略（优先级从高到低）：
 * 1. classpath:mock-data/{sanitized-path}.json 文件存在 → 加载作为响应
 * 2. 内置路径匹配 → 根据请求动态生成 Mock 数据
 * 3. 未匹配 → 返回空数据 + 警告日志
 *
 * 启用方式：application.yml 中设置 spring.profiles.active: mock
 */
public class MockDownstreamApiClient implements DownstreamApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockDownstreamApiClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==== 内置路径常量 ====
    private static final String PATH_CREDIT_SCORE    = "/v2/credit/score";
    private static final String PATH_FRAUD_RISK      = "/api/v1/fraud/score";
    private static final String PATH_OCCUPATION_RISK = "/v1/risk/evaluate";
    private static final String PATH_INCOME_VERIFY   = "/api/income/verify";
    private static final String PATH_PRODUCT_LIMIT   = "/api/limit/query";
    private static final String PATH_RISK_BATCH      = "/v1/risk/batch";
    private static final String PATH_HEALTH_SCORE    = "/v1/health/score";
    private static final String PATH_CREDIT_REPORT   = "/v1/credit/report";

    private final Random rng = new Random(42); // 固定种子保证可复现

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(ServiceConfig config, Map<String, Object> request) {
        String rawPath = config.getPath();
        log.info("[Mock] 拦截下游调用: path={}, request keys={}",
                rawPath, request != null ? request.keySet() : "null");

        if (request == null) {
            return Collections.emptyMap();
        }

        String path = extractPath(rawPath);

        // 1) 优先尝试加载 fixture 文件
        Map<String, Object> fixture = loadFixture(path);
        if (fixture != null) {
            return fixture;
        }

        // 2) 内置路径匹配 → 动态生成
        Map<String, Object> data = buildMockData(path, request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("message", "ok(mock)");
        response.put("data", data);
        return response;
    }

    @Override
    public Map<String, Object> callDirect(String url, String method, Map<String, Object> request, int timeout) {
        log.info("[Mock] callDirect 拦截: url={}", url);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("message", "ok(mock-direct)");
        response.put("data", Collections.emptyMap());
        return response;
    }

    // ======================== Fixture 加载 ========================

    /**
     * 从 classpath:mock-data/ 目录加载 JSON fixture 文件。
     * 文件名 = 路径中 `/` 替换为 `-`，去掉前导 `/`。
     *
     * 示例：
     *   path=/v1/risk/batch → mock-data/v1-risk-batch.json
     *   path=/v1/health/score → mock-data/v1-health-score.json
     */
    private Map<String, Object> loadFixture(String path) {
        if (path == null) return null;
        String fileName = path.startsWith("/") ? path.substring(1) : path;
        fileName = fileName.replace('/', '-') + ".json";
        String resourcePath = "mock-data/" + fileName;

        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.debug("[Mock] fixture 不存在: {}", resourcePath);
                return null;
            }
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> map = objectMapper.readValue(is,
                        new TypeReference<Map<String, Object>>() {});
                log.info("[Mock] 加载 fixture: {}", resourcePath);
                return map;
            }
        } catch (Exception e) {
            log.warn("[Mock] 加载 fixture 失败: {}, 原因: {}", resourcePath, e.getMessage());
            return null;
        }
    }

    // ======================== 内置动态生成 ========================

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMockData(String path, Map<String, Object> request) {
        if (PATH_CREDIT_SCORE.equals(path)) {
            return mockCreditScore(request);
        } else if (PATH_FRAUD_RISK.equals(path)) {
            return mockFraudRisk(request);
        } else if (PATH_OCCUPATION_RISK.equals(path)) {
            return mockOccupationRisk(request);
        } else if (PATH_INCOME_VERIFY.equals(path)) {
            return mockIncomeVerify(request);
        } else if (PATH_PRODUCT_LIMIT.equals(path)) {
            return mockProductLimit(request);
        } else if (PATH_RISK_BATCH.equals(path)) {
            return mockRiskBatch(request);
        } else if (PATH_HEALTH_SCORE.equals(path)) {
            return mockHealthScore(request);
        } else if (PATH_CREDIT_REPORT.equals(path)) {
            return mockCreditReport(request);
        } else {
            log.warn("[Mock] 未知路径 {}，返回空数据", path);
            return Collections.emptyMap();
        }
    }

    // ======================== 各下游 Mock 实现 ========================

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockCreditScore(Map<String, Object> request) {
        List<Map<String, Object>> scores = new ArrayList<>();
        List<Map<String, Object>> persons = (List<Map<String, Object>>) request.getOrDefault("persons", Collections.emptyList());

        for (Map<String, Object> person : persons) {
            List<String> customerNos = (List<String>) person.getOrDefault("customerNos", Collections.emptyList());
            for (String custNo : customerNos) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("customerNo", custNo);
                item.put("score", 600 + rng.nextInt(200));      // 600 ~ 799
                item.put("level", scoreToLevel(rng));
                item.put("scoreTime", System.currentTimeMillis());
                scores.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scores", scores);
        data.put("orderRiskLevel", rng.nextBoolean() ? "LOW" : "MEDIUM");
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockFraudRisk(Map<String, Object> request) {
        List<Map<String, Object>> persons = (List<Map<String, Object>>) request.getOrDefault("persons", Collections.emptyList());

        Set<String> allCustNos = new LinkedHashSet<>();
        for (Map<String, Object> person : persons) {
            List<String> customerNos = (List<String>) person.getOrDefault("customerNos", Collections.emptyList());
            allCustNos.addAll(customerNos);
        }

        int riskScore = rng.nextInt(100);
        String riskLevel = riskScore < 30 ? "LOW" : riskScore < 70 ? "MEDIUM" : "HIGH";
        List<String> hitRules = riskScore > 60
                ? Arrays.asList("FRAUD_RULE_001", "FRAUD_RULE_002")
                : Collections.emptyList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("riskScore", riskScore);
        data.put("riskLevel", riskLevel);
        data.put("hitRules", hitRules);
        data.put("hitCustomerNos", new ArrayList<>(allCustNos).subList(0, Math.min(1, allCustNos.size())));
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockOccupationRisk(Map<String, Object> request) {
        List<Map<String, Object>> insureds = (List<Map<String, Object>>) request.getOrDefault("insureds", Collections.emptyList());
        List<Map<String, Object>> risks = new ArrayList<>();

        for (Map<String, Object> ins : insureds) {
            List<String> customerNos = (List<String>) ins.getOrDefault("customerNos", Collections.emptyList());
            for (String custNo : customerNos) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("customerNo", custNo);
                item.put("riskClass", 1 + rng.nextInt(5));       // 1 ~ 5
                item.put("riskDescription", rng.nextBoolean() ? "低风险职业" : "中风险职业");
                risks.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("occupationRisks", risks);
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockIncomeVerify(Map<String, Object> request) {
        List<String> customerNos = (List<String>) request.getOrDefault("customerNos", Collections.emptyList());
        List<Map<String, Object>> records = new ArrayList<>();

        for (String custNo : customerNos) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("customerNo", custNo);
            rec.put("verified", rng.nextBoolean());
            rec.put("auditedIncome", 100000 + rng.nextInt(400000)); // 10w ~ 50w
            rec.put("incomeLevel", Arrays.asList("LOW", "MIDDLE", "HIGH").get(rng.nextInt(3)));
            records.add(rec);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incomeRecords", records);
        return data;
    }

    private Map<String, Object> mockProductLimit(Map<String, Object> request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("maxSumAssured", 5000000.0);
        data.put("maxDailyPremium", 5000.0);
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockRiskBatch(Map<String, Object> request) {
        List<Map<String, Object>> persons = (List<Map<String, Object>>) request.getOrDefault("persons", Collections.emptyList());
        List<Map<String, Object>> risks = new ArrayList<>();

        for (Map<String, Object> person : persons) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("refId", person.getOrDefault("refId", "UNKNOWN"));
            item.put("riskScore", 40 + rng.nextInt(60));     // 40 ~ 99
            item.put("fraudScore", rng.nextInt(100));         // 0 ~ 99
            item.put("amlFlag", rng.nextDouble() < 0.2);      // 20% 概率命中
            risks.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("risks", risks);
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockHealthScore(Map<String, Object> request) {
        Map<String, Object> data = new LinkedHashMap<>();
        int score = 50 + rng.nextInt(50); // 50 ~ 99
        data.put("healthScore", score);
        data.put("healthGrade",
                score >= 90 ? "EXCELLENT" : score >= 75 ? "GOOD" : score >= 60 ? "STANDARD" : "SUBSTANDARD");
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mockCreditReport(Map<String, Object> request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("creditScore", 500 + rng.nextInt(400)); // 500 ~ 899
        data.put("hasDefault", rng.nextDouble() < 0.3);   // 30% 概率
        data.put("loanCount", rng.nextInt(10));
        return data;
    }

    // ---- helper ----

    /**
     * 如果 path 是完整 URL（http/https 开头），提取路径部分；
     * 否则原样返回。确保与 DIRECT 模式下的完整 URL 配置兼容。
     */
    static String extractPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                return URI.create(path).getPath();
            } catch (Exception e) {
                log.warn("[Mock] 无法解析 URL path: {}", path);
                return path;
            }
        }
        return path;
    }

    private static String scoreToLevel(Random rng) {
        int n = rng.nextInt(4);
        switch (n) {
            case 0: return "A";
            case 1: return "B";
            case 2: return "C";
            default: return "D";
        }
    }

}
