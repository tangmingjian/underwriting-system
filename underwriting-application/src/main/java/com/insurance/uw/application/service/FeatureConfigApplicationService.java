package com.insurance.uw.application.service;

import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.GroovyMappingEngine;

import java.util.List;
import java.util.Optional;

/**
 * 特征配置应用服务
 */
public class FeatureConfigApplicationService {

    private final FeatureConfigRepository repository;
    private final FeatureScriptRepository scriptRepository;
    private final GroovyMappingEngine groovyEngine;

    public FeatureConfigApplicationService(FeatureConfigRepository repository,
                                           FeatureScriptRepository scriptRepository,
                                           GroovyMappingEngine groovyEngine) {
        this.repository = repository;
        this.scriptRepository = scriptRepository;
        this.groovyEngine = groovyEngine;
    }

    // ==================== 特征配置管理 ====================

    public List<FeatureConfig> listAll() {
        return repository.findAllEnabled();
    }

    public Optional<FeatureConfig> getByCode(String featureCode) {
        return repository.findByFeatureCode(featureCode);
    }

    public void create(FeatureConfig config) {
        repository.save(config);
    }

    public void update(FeatureConfig config) {
        repository.update(config);
        groovyEngine.evictScript(config.getFeatureCode());
        evictRelatedCaches(config);
    }

    public void delete(Long id) {
        repository.delete(id);
    }

    /**
     * 清除特征关联的所有缓存（Redis + 本地 Caffeine），包括特征码本身及其引用的脚本 ID。
     */
    public void evictScriptCache(String featureCode) {
        repository.evictCache(featureCode);
        groovyEngine.evictScript(featureCode);
        repository.findByFeatureCode(featureCode).ifPresent(this::evictRelatedCaches);
    }

    private void evictRelatedCaches(FeatureConfig config) {
        CalcConfig cc = config.getCalcConfig();
        if (cc != null) {
            if (cc.getInputScriptId() != null) {
                scriptRepository.evictCache(cc.getInputScriptId());
                groovyEngine.evictScript(cc.getInputScriptId());
            }
            if (cc.getOutputScriptId() != null) {
                scriptRepository.evictCache(cc.getOutputScriptId());
                groovyEngine.evictScript(cc.getOutputScriptId());
            }
        }
    }

    // ==================== 脚本管理 ====================

    public List<FeatureScript> listScripts() {
        return scriptRepository.findAllEnabled();
    }

    public Optional<FeatureScript> getScript(String scriptId) {
        return scriptRepository.findByScriptId(scriptId);
    }

    public void saveScript(FeatureScript script) {
        scriptRepository.save(script);
    }

    public void updateScript(FeatureScript script) {
        scriptRepository.update(script);
        groovyEngine.evictScript(script.getScriptId());
    }

    public void deleteScript(Long id) {
        scriptRepository.delete(id);
    }

}
