package com.insurance.uw.application.service;

import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;

/**
 * 特征取数服务接口 — 内部服务契约（供 Controllers 本地注入）
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
