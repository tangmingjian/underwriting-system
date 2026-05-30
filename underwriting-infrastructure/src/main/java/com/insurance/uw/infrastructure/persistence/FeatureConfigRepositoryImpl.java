package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;

import java.util.List;
import java.util.Optional;

/**
 * 特征配置仓储实现
 */
public class FeatureConfigRepositoryImpl implements FeatureConfigRepository {

    private final FeatureConfigMapper mapper;

    public FeatureConfigRepositoryImpl(FeatureConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<FeatureConfig> findByFeatureCode(String featureCode) {
        FeatureConfig config = mapper.selectOne(
                new LambdaQueryWrapper<FeatureConfig>()
                        .eq(FeatureConfig::getFeatureCode, featureCode));
        return Optional.ofNullable(config);
    }

    @Override
    public List<FeatureConfig> findAllEnabled() {
        return mapper.selectList(
                new LambdaQueryWrapper<FeatureConfig>()
                        .eq(FeatureConfig::getStatus, FeatureStatus.ACTIVE));
    }

    @Override
    public List<FeatureConfig> findByFeatureCodes(List<String> featureCodes) {
        if (featureCodes == null || featureCodes.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(
                new LambdaQueryWrapper<FeatureConfig>()
                        .in(FeatureConfig::getFeatureCode, featureCodes)
                        .eq(FeatureConfig::getStatus, FeatureStatus.ACTIVE));
    }

    @Override
    public void save(FeatureConfig config) {
        mapper.insert(config);
    }

    @Override
    public void update(FeatureConfig config) {
        mapper.updateById(config);
    }

    @Override
    public void delete(Long id) {
        mapper.deleteById(id);
    }

}
