package com.insurance.uw.domain.repository;

import com.insurance.uw.domain.model.entity.FeatureScript;

import java.util.List;
import java.util.Optional;

/**
 * 特征脚本仓储接口
 */
public interface FeatureScriptRepository {

    Optional<FeatureScript> findByScriptId(String scriptId);

    List<FeatureScript> findAllEnabled();

    void save(FeatureScript script);

    void update(FeatureScript script);

    void delete(Long id);

    /**
     * 清除指定脚本 ID 的 Redis 缓存
     */
    void evictCache(String scriptId);

}
