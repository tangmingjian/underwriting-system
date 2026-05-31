package com.insurance.uw.infrastructure.client;

import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.service.DownstreamApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;

/**
 * 下游 API Mock 实现 — 用于本地测试，不依赖真实下游服务。
 *
 * 根据 ServiceConfig.path 自动匹配对应的 Mock 响应结构，
 * 从请求中读取 customerNos / persons / insureds 动态生成数据。
 *
 * 启用方式：application.yml 中设置 spring.profiles.active: mock
 */
public class MockDownstreamApiClient implements DownstreamApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockDownstreamApiClient.class);

    private static final String PATH_CREDIT_SCORE    = "/v2/credit/score";
    private static final String PATH_FRAUD_RISK      = "/api/v1/fraud/score";
    private static final String PATH_OCCUPATION_RISK = "/v1/risk/evaluate";
    private static final String PATH_INCOME_VERIFY   = "/api/income/verify";
    private static final String PATH_PRODUCT_LIMIT   = "/api/limit/query";

    private final Random rng = new Random(42); // 固定种子保证可复现

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(ServiceConfig config, Map<String, Object> request) {
        String rawPath = config.getPath();
        log.info("[Mock] 拦截下游调用: path={}, request keys={}", rawPath, request != null ? request.keySet() : "null");

        if (request == null) {
            return Collections.emptyMap();
        }

        // 如果 path 是完整 URL（如 https://host/api/limit/query），提取路径部分以匹配
        String path = extractPath(rawPath);

        Map<String, Object> data;
        if (PATH_CREDIT_SCORE.equals(path)) {
            data = mockCreditScore(request);
        } else if (PATH_FRAUD_RISK.equals(path)) {
            data = mockFraudRisk(request);
        } else if (PATH_OCCUPATION_RISK.equals(path)) {
            data = mockOccupationRisk(request);
        } else if (PATH_INCOME_VERIFY.equals(path)) {
            data = mockIncomeVerify(request);
        } else if (PATH_PRODUCT_LIMIT.equals(path)) {
            data = mockProductLimit(request);
        } else {
            log.warn("[Mock] 未知路径 {}（原始: {}），返回空数据", path, rawPath);
            data = Collections.emptyMap();
        }

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

    // ---- helper ----

    /**
     * 如果 path 是完整 URL（http/https 开头），提取路径部分；
     * 否则原样返回。确保与 DIRECT 模式下的完整 URL 配置兼容。
     */
    private static String extractPath(String path) {
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
