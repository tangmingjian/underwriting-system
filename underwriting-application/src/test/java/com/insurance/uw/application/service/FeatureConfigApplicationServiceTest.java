package com.insurance.uw.application.service;

import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.GroovyMappingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FeatureConfigApplicationService - 特征配置应用服务")
@ExtendWith(MockitoExtension.class)
class FeatureConfigApplicationServiceTest {

    @Mock
    private FeatureConfigRepository featureConfigRepository;

    @Mock
    private FeatureScriptRepository scriptRepository;

    @Mock
    private GroovyMappingEngine groovyEngine;

    private FeatureConfigApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FeatureConfigApplicationService(
                featureConfigRepository, scriptRepository, groovyEngine);
    }

    @Nested
    @DisplayName("特征配置 CRUD")
    class FeatureConfigCrud {

        @Test
        @DisplayName("listAll → 委托给 repository.findAllEnabled()")
        void listAll() {
            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of());

            List<FeatureConfig> result = service.listAll();

            assertThat(result).isEmpty();
            verify(featureConfigRepository).findAllEnabled();
        }

        @Test
        @DisplayName("getByCode → 委托给 repository.findByFeatureCode()")
        void getByCode() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("FC1");
            when(featureConfigRepository.findByFeatureCode("FC1")).thenReturn(Optional.of(fc));

            Optional<FeatureConfig> result = service.getByCode("FC1");

            assertThat(result).isPresent();
            assertThat(result.get().getFeatureCode()).isEqualTo("FC1");
        }

        @Test
        @DisplayName("create → 委托给 repository.save()")
        void create() {
            FeatureConfig fc = new FeatureConfig();
            service.create(fc);

            verify(featureConfigRepository).save(fc);
        }

        @Test
        @DisplayName("update → 更新并清除关联缓存")
        void updateEvictsCache() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("FC1");
            CalcConfig calcConfig = new CalcConfig();
            calcConfig.setInputScriptId("in-script");
            calcConfig.setOutputScriptId("out-script");
            fc.setCalcConfig(calcConfig);

            service.update(fc);

            verify(featureConfigRepository).update(fc);
            verify(groovyEngine).evictScript("FC1");
            verify(groovyEngine).evictScript("in-script");
            verify(groovyEngine).evictScript("out-script");
        }

        @Test
        @DisplayName("update → calcConfig 为 null 时只清除 featureCode 缓存")
        void updateNullCalcConfig() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("FC1");

            service.update(fc);

            verify(featureConfigRepository).update(fc);
            verify(groovyEngine).evictScript("FC1");
            verify(groovyEngine, never()).evictScript("in-script");
            verify(groovyEngine, never()).evictScript("out-script");
        }

        @Test
        @DisplayName("delete → 委托给 repository.delete()")
        void delete() {
            service.delete(1L);

            verify(featureConfigRepository).delete(1L);
        }
    }

    @Nested
    @DisplayName("缓存清除")
    class CacheEviction {

        @Test
        @DisplayName("evictScriptCache → featureCode 存在时清除 featureCode + 关联脚本缓存")
        void evictByFeatureCode() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("FC1");
            CalcConfig cc = new CalcConfig();
            cc.setInputScriptId("in-1");
            fc.setCalcConfig(cc);

            when(featureConfigRepository.findByFeatureCode("FC1")).thenReturn(Optional.of(fc));

            service.evictScriptCache("FC1");

            verify(groovyEngine, times(2)).evictScript("FC1");
            verify(groovyEngine).evictScript("in-1");
        }

        @Test
        @DisplayName("evictScriptCache → featureCode 不存在时只清除 featureCode")
        void evictWhenFeatureNotFound() {
            when(featureConfigRepository.findByFeatureCode("FCX")).thenReturn(Optional.empty());

            service.evictScriptCache("FCX");

            verify(groovyEngine).evictScript("FCX");
            verify(featureConfigRepository).findByFeatureCode("FCX");
        }
    }

    @Nested
    @DisplayName("脚本管理")
    class ScriptManagement {

        @Test
        @DisplayName("listScripts → 委托给 scriptRepository.findAllEnabled()")
        void listScripts() {
            when(scriptRepository.findAllEnabled()).thenReturn(List.of());

            List<FeatureScript> result = service.listScripts();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getScript → 委托给 scriptRepository.findByScriptId()")
        void getScript() {
            FeatureScript script = new FeatureScript();
            script.setScriptId("s1");
            when(scriptRepository.findByScriptId("s1")).thenReturn(Optional.of(script));

            Optional<FeatureScript> result = service.getScript("s1");

            assertThat(result).isPresent();
            assertThat(result.get().getScriptId()).isEqualTo("s1");
        }

        @Test
        @DisplayName("saveScript → 委托给 scriptRepository.save()")
        void saveScript() {
            FeatureScript script = new FeatureScript();
            service.saveScript(script);

            verify(scriptRepository).save(script);
        }

        @Test
        @DisplayName("updateScript → 更新并清除脚本缓存")
        void updateScript() {
            FeatureScript script = new FeatureScript();
            script.setScriptId("s1");

            service.updateScript(script);

            verify(scriptRepository).update(script);
            verify(groovyEngine).evictScript("s1");
        }

        @Test
        @DisplayName("deleteScript → 委托给 scriptRepository.delete()")
        void deleteScript() {
            service.deleteScript(1L);

            verify(scriptRepository).delete(1L);
        }
    }
}
