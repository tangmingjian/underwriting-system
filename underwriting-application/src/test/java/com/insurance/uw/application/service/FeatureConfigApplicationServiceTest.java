package com.insurance.uw.application.service;

import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.engine.core.service.GroovyMappingEngine;
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
        @DisplayName("update → 收集新旧 scriptId，双重清除包裹 repository.update()")
        void updateEvictsCache() {
            FeatureConfig oldFc = new FeatureConfig();
            oldFc.setFeatureCode("FC1");
            CalcConfig oldCc = new CalcConfig();
            oldCc.setInputScriptId("old-in");
            oldCc.setOutputScriptId("old-out");
            oldFc.setCalcConfig(oldCc);

            FeatureConfig newFc = new FeatureConfig();
            newFc.setFeatureCode("FC1");
            CalcConfig newCc = new CalcConfig();
            newCc.setInputScriptId("in-script");
            newCc.setOutputScriptId("out-script");
            newFc.setCalcConfig(newCc);

            when(featureConfigRepository.findByFeatureCodeDirect("FC1")).thenReturn(Optional.of(oldFc));

            service.update(newFc);

            // 只读一次 DB
            verify(featureConfigRepository).findByFeatureCodeDirect("FC1");
            // update 在两个 evictScriptCaches 之间调用
            var inOrder = inOrder(scriptRepository, groovyEngine, featureConfigRepository);
            // 第一遍清除：旧 + 新 scriptId
            inOrder.verify(scriptRepository).evictCache("old-in");
            inOrder.verify(groovyEngine).evictScript("old-in");
            inOrder.verify(scriptRepository).evictCache("old-out");
            inOrder.verify(groovyEngine).evictScript("old-out");
            inOrder.verify(scriptRepository).evictCache("in-script");
            inOrder.verify(groovyEngine).evictScript("in-script");
            inOrder.verify(scriptRepository).evictCache("out-script");
            inOrder.verify(groovyEngine).evictScript("out-script");
            // repository.update 在第一遍和第二遍之间
            inOrder.verify(featureConfigRepository).update(newFc);
            // 第二遍清除：同旧 + 新 scriptId
            inOrder.verify(scriptRepository).evictCache("old-in");
            inOrder.verify(groovyEngine).evictScript("old-in");
            inOrder.verify(scriptRepository).evictCache("old-out");
            inOrder.verify(groovyEngine).evictScript("old-out");
            inOrder.verify(scriptRepository).evictCache("in-script");
            inOrder.verify(groovyEngine).evictScript("in-script");
            inOrder.verify(scriptRepository).evictCache("out-script");
            inOrder.verify(groovyEngine).evictScript("out-script");
        }

        @Test
        @DisplayName("update → 旧配置不存在时双重清除只含新 scriptId")
        void updateNoOldConfig() {
            FeatureConfig newFc = new FeatureConfig();
            newFc.setFeatureCode("FC1");
            CalcConfig newCc = new CalcConfig();
            newCc.setInputScriptId("in-script");
            newFc.setCalcConfig(newCc);

            when(featureConfigRepository.findByFeatureCodeDirect("FC1")).thenReturn(Optional.empty());

            service.update(newFc);

            var inOrder = inOrder(scriptRepository, groovyEngine, featureConfigRepository);
            // 第一遍清除
            inOrder.verify(scriptRepository).evictCache("in-script");
            inOrder.verify(groovyEngine).evictScript("in-script");
            // update 在中间
            inOrder.verify(featureConfigRepository).update(newFc);
            // 第二遍清除
            inOrder.verify(scriptRepository).evictCache("in-script");
            inOrder.verify(groovyEngine).evictScript("in-script");
            // 没有旧 scriptId 的清理
            verify(scriptRepository, never()).evictCache("old-in");
            verify(groovyEngine, never()).evictScript("old-in");
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
        @DisplayName("evictScriptCache → 一次读 DB，双重清除包裹 FC evict")
        void evictByFeatureCode() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("FC1");
            CalcConfig cc = new CalcConfig();
            cc.setInputScriptId("in-1");
            fc.setCalcConfig(cc);

            when(featureConfigRepository.findByFeatureCodeDirect("FC1")).thenReturn(Optional.of(fc));

            service.evictCache("FC1");

            // 只读一次 DB
            verify(featureConfigRepository).findByFeatureCodeDirect("FC1");
            // 顺序验证：第一遍清脚本 → 清 FC → 第二遍清脚本
            var inOrder = inOrder(scriptRepository, groovyEngine, featureConfigRepository);
            inOrder.verify(scriptRepository).evictCache("in-1");
            inOrder.verify(groovyEngine).evictScript("in-1");
            inOrder.verify(featureConfigRepository).evictCache("FC1");
            inOrder.verify(scriptRepository).evictCache("in-1");
            inOrder.verify(groovyEngine).evictScript("in-1");
        }

        @Test
        @DisplayName("evictScriptCache → featureCode 不存在时只清除 FC 缓存")
        void evictWhenFeatureNotFound() {
            when(featureConfigRepository.findByFeatureCodeDirect("FCX")).thenReturn(Optional.empty());

            service.evictCache("FCX");

            // 只读一次 DB
            verify(featureConfigRepository).findByFeatureCodeDirect("FCX");
            verify(featureConfigRepository).evictCache("FCX");
            verify(scriptRepository, never()).evictCache(anyString());
            verify(groovyEngine, never()).evictScript(anyString());
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
        @DisplayName("deleteScript → 先查出 scriptId，删除后清除 Groovy 缓存")
        void deleteScript() {
            FeatureScript script = new FeatureScript();
            script.setScriptId("s1");
            when(scriptRepository.findById(1L)).thenReturn(Optional.of(script));

            service.deleteScript(1L);

            verify(scriptRepository).findById(1L);
            verify(scriptRepository).delete(1L);
            verify(groovyEngine).evictScript("s1");
        }
    }
}
