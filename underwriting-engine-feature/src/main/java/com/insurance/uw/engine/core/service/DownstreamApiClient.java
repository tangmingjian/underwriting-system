package com.insurance.uw.engine.core.service;

import com.insurance.uw.engine.core.model.valueobject.ServiceConfig;

import java.util.Map;

/**
 * 下游 HTTP API 调用客户端接口
 */
public interface DownstreamApiClient {

    /**
     * 通过服务配置调用下游接口（支持 NACOS / DIRECT 服务发现）
     *
     * @param serviceConfig 服务配置
     * @param request       请求参数
     * @return 响应 Map
     */
    Map<String, Object> call(ServiceConfig serviceConfig, Map<String, Object> request);

    /**
     * 直接 URL 调用（兼容模式）
     */
    Map<String, Object> callDirect(String url, String method, Map<String, Object> request, int timeout);

}
