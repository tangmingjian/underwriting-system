package com.insurance.uw.application.service;

import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.engine.core.service.GroovyMappingEngine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        Set<String> scriptIds = new LinkedHashSet<>();
        repository.findByFeatureCodeDirect(config.getFeatureCode())
                .ifPresent(old -> scriptIds.addAll(collectScriptIds(old)));
        scriptIds.addAll(collectScriptIds(config));
        List<String> idList = new ArrayList<>(scriptIds);

        evictScriptCaches(idList);
        repository.update(config); // 内部 evict FC 缓存
        evictScriptCaches(idList);
    }

    public void delete(Long id) {
        repository.delete(id);
    }

    /**
     * 清除特征关联的所有缓存（Redis + 本地 Caffeine）。
     * 双重清除：evict FC 缓存前后各清一次脚本缓存，收窄两次 evict 之间被重新缓存的窗口。
     */
    public void evictCache(String featureCode) {
        List<String> scriptIds = repository.findByFeatureCodeDirect(featureCode)
                .map(this::collectScriptIds).orElse(List.of());
        evictScriptCaches(scriptIds);
        repository.evictCache(featureCode);
        evictScriptCaches(scriptIds);
    }

    private List<String> collectScriptIds(FeatureConfig fc) {
        List<String> ids = new ArrayList<>();
        CalcConfig cc = fc.getCalcConfig();
        if (cc != null) {
            if (cc.getInputScriptId() != null) ids.add(cc.getInputScriptId());
            if (cc.getOutputScriptId() != null) ids.add(cc.getOutputScriptId());
        }
        return ids;
    }

    private void evictScriptCaches(List<String> scriptIds) {
        for (String id : scriptIds) {
            scriptRepository.evictCache(id);
            groovyEngine.evictScript(id);
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
        FeatureScript script = scriptRepository.findById(id).orElse(null);
        scriptRepository.delete(id);
        if (script != null) {
            groovyEngine.evictScript(script.getScriptId());
        }
    }

}
