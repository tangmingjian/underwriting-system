package com.insurance.uw.domain.repository;

import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.List;
import java.util.Optional;

/**
 * 特征配置仓储接口
 */
public interface FeatureConfigRepository {

    Optional<FeatureConfig> findByFeatureCode(String featureCode);

    List<FeatureConfig> findAllEnabled();

    List<FeatureConfig> findByFeatureCodes(List<String> featureCodes);

    void save(FeatureConfig config);

    void update(FeatureConfig config);

    void delete(Long id);

    /**
     * 清除指定特征码的 Redis 缓存
     */
    void evictCache(String featureCode);

}
