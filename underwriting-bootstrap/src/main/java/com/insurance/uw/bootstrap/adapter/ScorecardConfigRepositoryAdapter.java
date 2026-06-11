package com.insurance.uw.bootstrap.adapter;

import com.insurance.uw.domain.repository.ScorecardConfigRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 引擎 ScorecardConfigRepository 适配器 —— 实现引擎接口，委托给领域仓库。
 */
public class ScorecardConfigRepositoryAdapter
        implements com.insurance.uw.engine.core.repository.ScorecardConfigRepository {

    private final ScorecardConfigRepository delegate;

    public ScorecardConfigRepositoryAdapter(ScorecardConfigRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<com.insurance.uw.engine.core.model.entity.ScorecardConfig> findByScorecardCode(String scorecardCode) {
        return delegate.findByScorecardCode(scorecardCode).map(ModelAdapter::toEngine);
    }

    @Override
    public List<com.insurance.uw.engine.core.model.entity.ScorecardConfig> findAllEnabled() {
        return delegate.findAllEnabled().stream()
                .map(ModelAdapter::toEngine)
                .collect(Collectors.toList());
    }

    @Override
    public void save(com.insurance.uw.engine.core.model.entity.ScorecardConfig config) {
        delegate.save(ModelAdapter.toDomain(config));
    }

    @Override
    public void update(com.insurance.uw.engine.core.model.entity.ScorecardConfig config) {
        delegate.update(ModelAdapter.toDomain(config));
    }

    @Override
    public void delete(Long id) {
        delegate.delete(id);
    }
}
