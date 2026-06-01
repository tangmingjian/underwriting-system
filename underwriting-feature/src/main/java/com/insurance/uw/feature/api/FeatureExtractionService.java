package com.insurance.uw.feature.api;

/**
 * 特征取数服务接口 — 跨模块/跨进程的远程契约
 *
 * 后期独立部署时，规则服务侧通过 HTTP 客户端实现此接口，
 * 特征服务侧提供此接口的 HTTP 端点。
 */
public interface FeatureExtractionService {

    /**
     * 按需执行特征取数
     *
     * @param request 包含订单、特征码列表、实体映射
     * @return 特征结果（全序列化）
     */
    FeatureExtractionResult extract(FeatureExtractionRequest request);
}
