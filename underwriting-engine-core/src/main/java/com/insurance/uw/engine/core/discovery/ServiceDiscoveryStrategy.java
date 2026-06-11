package com.insurance.uw.engine.core.discovery;

import com.insurance.uw.engine.core.model.valueobject.ServiceConfig;

/**
 * 服务发现策略接口
 */
public interface ServiceDiscoveryStrategy {

    /**
     * 是否支持该发现类型
     */
    boolean supports(String discoveryType);

    /**
     * 根据服务配置解析出完整的调用 URL
     *
     * @param config 服务配置（含 discoveryType、path、serviceName 等）
     * @return 完整 URL（如 http://10.0.0.1:8080/api/v1/fraud/score）
     */
    String resolveUrl(ServiceConfig config);

}
