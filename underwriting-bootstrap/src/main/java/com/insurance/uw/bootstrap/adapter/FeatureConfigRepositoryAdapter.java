package com.insurance.uw.bootstrap.adapter;

import com.insurance.uw.domain.repository.FeatureConfigRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 引擎 FeatureConfigRepository 适配器 —— 实现引擎接口，委托给领域仓库。
 */
public class FeatureConfigRepositoryAdapter
        implements com.insurance.uw.engine.core.repository.FeatureConfigRepository {

    private final FeatureConfigRepository delegate;

    public FeatureConfigRepositoryAdapter(FeatureConfigRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<com.insurance.uw.engine.core.model.entity.FeatureConfig> findByFeatureCode(String featureCode) {
        return delegate.findByFeatureCode(featureCode).map(ModelAdapter::toEngine);
    }

    @Override
    public List<com.insurance.uw.engine.core.model.entity.FeatureConfig> findAllEnabled() {
        return delegate.findAllEnabled().stream()
                .map(ModelAdapter::toEngine)
                .collect(Collectors.toList());
    }

    @Override
    public List<com.insurance.uw.engine.core.model.entity.FeatureConfig> findEnabledByCalcType(
            com.insurance.uw.engine.core.enums.CalcType calcType) {
        com.insurance.uw.common.enums.CalcType domainCalcType =
                com.insurance.uw.common.enums.CalcType.valueOf(calcType.name());
        return delegate.findEnabledByCalcType(domainCalcType).stream()
                .map(ModelAdapter::toEngine)
                .collect(Collectors.toList());
    }

    @Override
    public List<com.insurance.uw.engine.core.model.entity.FeatureConfig> findByFeatureCodes(List<String> featureCodes) {
        return delegate.findByFeatureCodes(featureCodes).stream()
                .map(ModelAdapter::toEngine)
                .collect(Collectors.toList());
    }

    @Override
    public void save(com.insurance.uw.engine.core.model.entity.FeatureConfig config) {
        delegate.save(ModelAdapter.toDomain(config));
    }

    @Override
    public void update(com.insurance.uw.engine.core.model.entity.FeatureConfig config) {
        delegate.update(ModelAdapter.toDomain(config));
    }

    @Override
    public Optional<com.insurance.uw.engine.core.model.entity.FeatureConfig> findByFeatureCodeDirect(String featureCode) {
        return delegate.findByFeatureCodeDirect(featureCode).map(ModelAdapter::toEngine);
    }

    @Override
    public void delete(Long id) {
        delegate.delete(id);
    }

    @Override
    public void evictCache(String featureCode) {
        delegate.evictCache(featureCode);
    }
}
