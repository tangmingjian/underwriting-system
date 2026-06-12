package com.insurance.uw.engine.core.discovery;

import com.insurance.uw.engine.core.model.valueobject.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 静态多端点服务发现策略
 *
 * 从 static_endpoints 列表中随机选取一个端点，
 * 拼接 path 得到完整 URL。
 */
public class StaticServiceDiscoveryStrategy implements ServiceDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(StaticServiceDiscoveryStrategy.class);

    @Override
    public boolean supports(String discoveryType) {
        return "STATIC".equalsIgnoreCase(discoveryType);
    }

    @Override
    public String resolveUrl(ServiceConfig config) {
        List<String> endpoints = config.getStaticEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("STATIC 模式下 service.static_endpoints 不能为空");
        }

        // 随机选一个端点（简单负载均衡）
        int idx = endpoints.size() == 1 ? 0 : ThreadLocalRandom.current().nextInt(endpoints.size());
        String endpoint = endpoints.get(idx);

        // 去掉末尾的 /
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }

        // 拼接 path（path 以 / 开头或为空）
        String path = config.getPath() != null ? config.getPath() : "";
        String url = endpoint + path;

        log.debug("STATIC 端点选择: {}/{} → {}", endpoints.size(), idx + 1, url);
        return url;
    }

}
