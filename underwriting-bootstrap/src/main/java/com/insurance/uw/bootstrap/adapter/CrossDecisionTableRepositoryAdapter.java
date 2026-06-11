package com.insurance.uw.bootstrap.adapter;

import com.insurance.uw.domain.repository.CrossDecisionTableRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 引擎 CrossDecisionTableRepository 适配器 —— 实现引擎接口，委托给领域仓库。
 */
public class CrossDecisionTableRepositoryAdapter
        implements com.insurance.uw.engine.core.repository.CrossDecisionTableRepository {

    private final CrossDecisionTableRepository delegate;

    public CrossDecisionTableRepositoryAdapter(CrossDecisionTableRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<com.insurance.uw.engine.core.model.entity.CrossDecisionTable> findByTableCode(String tableCode) {
        return delegate.findByTableCode(tableCode).map(ModelAdapter::toEngine);
    }

    @Override
    public List<com.insurance.uw.engine.core.model.entity.CrossDecisionTable> findAllEnabled() {
        return delegate.findAllEnabled().stream()
                .map(ModelAdapter::toEngine)
                .collect(Collectors.toList());
    }

    @Override
    public void save(com.insurance.uw.engine.core.model.entity.CrossDecisionTable table) {
        delegate.save(ModelAdapter.toDomain(table));
    }

    @Override
    public void update(com.insurance.uw.engine.core.model.entity.CrossDecisionTable table) {
        delegate.update(ModelAdapter.toDomain(table));
    }

    @Override
    public void delete(Long id) {
        delegate.delete(id);
    }
}
