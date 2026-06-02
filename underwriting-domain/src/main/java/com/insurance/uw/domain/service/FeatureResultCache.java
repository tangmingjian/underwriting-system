package com.insurance.uw.domain.service;

import java.util.Optional;

/**
 * 特征计算结果缓存接口（跨请求）
 *
 * 缓存键维度：特征码 + 目标ID（被保人 / 保单 / 投保人 / 订单）
 * TTL 来源于 t_feature_config.ttl_seconds 字段
 */
public interface FeatureResultCache {

    /**
     * 获取缓存的特征计算结果
     * @param featureCode 特征码
     * @param targetId    目标ID（insuredId / policyId / applicantId / __ORDER__）
     */
    Optional<Object> get(String featureCode, String targetId);

    /**
     * 写入特征计算结果缓存
     * @param featureCode 特征码
     * @param targetId    目标ID
     * @param value       计算结果
     * @param ttlSeconds  过期时间（秒）
     */
    void put(String featureCode, String targetId, Object value, int ttlSeconds);
}
