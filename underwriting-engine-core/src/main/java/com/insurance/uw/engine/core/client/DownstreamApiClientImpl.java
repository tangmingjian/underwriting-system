package com.insurance.uw.engine.core.client;

import com.insurance.uw.engine.core.model.valueobject.ServiceConfig;
import com.insurance.uw.engine.core.service.DownstreamApiClient;
import com.insurance.uw.engine.core.discovery.ServiceDiscoveryRouter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

/**
 * 下游 HTTP API 调用客户端实现
 *
 * 通过 ServiceDiscoveryRouter 支持多种服务发现策略（NACOS / DIRECT）。
 */
public class DownstreamApiClientImpl implements DownstreamApiClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamApiClientImpl.class);

    private final RestTemplate restTemplate;
    private final ServiceDiscoveryRouter router;
    private final CircuitBreaker circuitBreaker;

    public DownstreamApiClientImpl(RestTemplate restTemplate, ServiceDiscoveryRouter router,
                                    CircuitBreaker circuitBreaker) {
        this.restTemplate = restTemplate;
        this.router = router;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Map<String, Object> call(ServiceConfig config, Map<String, Object> request) {
        // 1. 通过路由解析服务地址
        String url = router.resolveUrl(config);
        String method = config.getMethod() != null ? config.getMethod().toUpperCase() : "POST";
        int timeout = config.getTimeoutMs() != null ? config.getTimeoutMs() : 10000;

        // 2. HTTP 调用
        return doCall(url, method, request, config.getHeaders());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> callDirect(String url, String method, Map<String, Object> request, int timeout) {
        return doCall(url, method, request, Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doCall(String url, String method, Map<String, Object> request,
                                       Map<String, String> customHeaders) {
        HttpMethod httpMethod = "GET".equalsIgnoreCase(method) ? HttpMethod.GET : HttpMethod.POST;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 自定义请求头（支持占位符，如 ${ctx.traceId}，此处保留原始值由调用方预处理）
        if (customHeaders != null) {
            customHeaders.forEach(headers::set);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        log.debug("调用下游接口: {} {} 请求体: {}", method, url, request);
        ResponseEntity<Map> response = circuitBreaker.executeSupplier(() ->
                restTemplate.exchange(url, httpMethod, entity, Map.class));

        if (response.getBody() != null) {
            return response.getBody();
        }
        return Collections.emptyMap();
    }

}
