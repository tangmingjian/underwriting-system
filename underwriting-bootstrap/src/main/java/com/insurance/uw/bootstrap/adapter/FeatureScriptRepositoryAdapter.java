package com.insurance.uw.bootstrap.adapter;

import com.insurance.uw.domain.repository.FeatureScriptRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 引擎 FeatureScriptRepository 适配器 —— 实现引擎接口，委托给领域仓库。
 */
public class FeatureScriptRepositoryAdapter
        implements com.insurance.uw.engine.core.repository.FeatureScriptRepository {

    private final FeatureScriptRepository delegate;

    public FeatureScriptRepositoryAdapter(FeatureScriptRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<com.insurance.uw.engine.core.model.entity.FeatureScript> findByScriptId(String scriptId) {
        return delegate.findByScriptId(scriptId).map(ModelAdapter::toEngine);
    }

    @Override
    public Optional<com.insurance.uw.engine.core.model.entity.FeatureScript> findById(Long id) {
        return delegate.findById(id).map(ModelAdapter::toEngine);
    }

    @Override
    public List<com.insurance.uw.engine.core.model.entity.FeatureScript> findAllEnabled() {
        return delegate.findAllEnabled().stream()
                .map(ModelAdapter::toEngine)
                .collect(Collectors.toList());
    }

    @Override
    public void save(com.insurance.uw.engine.core.model.entity.FeatureScript script) {
        delegate.save(ModelAdapter.toDomain(script));
    }

    @Override
    public void update(com.insurance.uw.engine.core.model.entity.FeatureScript script) {
        delegate.update(ModelAdapter.toDomain(script));
    }

    @Override
    public void delete(Long id) {
        delegate.delete(id);
    }

    @Override
    public void evictCache(String scriptId) {
        delegate.evictCache(scriptId);
    }
}
