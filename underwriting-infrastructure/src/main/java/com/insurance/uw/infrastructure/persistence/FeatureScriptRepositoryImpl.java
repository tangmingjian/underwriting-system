package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.repository.FeatureScriptRepository;

import java.util.List;
import java.util.Optional;

public class FeatureScriptRepositoryImpl implements FeatureScriptRepository {

    private final FeatureScriptMapper mapper;

    public FeatureScriptRepositoryImpl(FeatureScriptMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<FeatureScript> findByScriptId(String scriptId) {
        FeatureScript script = mapper.selectOne(
                new LambdaQueryWrapper<FeatureScript>()
                        .eq(FeatureScript::getScriptId, scriptId)
                        .eq(FeatureScript::getStatus, FeatureStatus.ACTIVE)
                        .orderByDesc(FeatureScript::getVersion)
                        .last("LIMIT 1"));
        return Optional.ofNullable(script);
    }

    @Override
    public List<FeatureScript> findAllEnabled() {
        return mapper.selectList(
                new LambdaQueryWrapper<FeatureScript>()
                        .eq(FeatureScript::getStatus, FeatureStatus.ACTIVE));
    }

    @Override
    public void save(FeatureScript script) {
        mapper.insert(script);
    }

    @Override
    public void update(FeatureScript script) {
        mapper.updateById(script);
    }

    @Override
    public void delete(Long id) {
        mapper.deleteById(id);
    }

}
