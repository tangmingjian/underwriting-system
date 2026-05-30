package com.insurance.uw.infrastructure.client.discovery;

import com.insurance.uw.domain.model.valueobject.ServiceConfig;

/**
 * 静态域名 / 直连服务发现策略
 *
 * 直接使用 calc_config.service.path 作为完整 URL，
 * 例如：https://api.risk.example.com/v1/fraud/score
 */
public class DirectServiceDiscoveryStrategy implements ServiceDiscoveryStrategy {

    @Override
    public boolean supports(String discoveryType) {
        return discoveryType == null || "DIRECT".equalsIgnoreCase(discoveryType);
    }

    @Override
    public String resolveUrl(ServiceConfig config) {
        String path = config.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("DIRECT 模式下 service.path 不能为空");
        }
        // 如果 path 已经是完整 URL（含协议），直接返回
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        // 否则拼接协议 + path
        String protocol = config.getProtocol() != null ? config.getProtocol().toLowerCase() : "http";
        return protocol + "://" + path;
    }

}
