package com.insurance.uw.engine.core.discovery;

import com.insurance.uw.engine.core.model.valueobject.ServiceConfig;

import java.util.List;

/**
 * 服务发现路由器 —— 根据 discoveryType 选择对应策略
 */
public class ServiceDiscoveryRouter {

    private final List<ServiceDiscoveryStrategy> strategies;

    public ServiceDiscoveryRouter(List<ServiceDiscoveryStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 解析服务地址
     */
    public String resolveUrl(ServiceConfig config) {
        String discoveryType = config.getDiscoveryType();
        for (ServiceDiscoveryStrategy strategy : strategies) {
            if (strategy.supports(discoveryType)) {
                return strategy.resolveUrl(config);
            }
        }
        throw new IllegalArgumentException("不支持的服务发现类型: " + discoveryType);
    }

}
