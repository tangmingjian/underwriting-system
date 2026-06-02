package com.insurance.uw.infrastructure.groovy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.insurance.uw.domain.service.GroovyMappingEngine;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.time.Duration;

/**
 * Groovy 脚本映射引擎实现
 *
 * 基于 GroovyClassLoader 实现脚本编译、缓存、调用。
 * 与设计文档 2.2 节保持一致。
 */
public class GroovyMappingEngineImpl implements GroovyMappingEngine {

    private final GroovyClassLoader classLoader;
    private final Cache<String, Class<GroovyObject>> scriptCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public GroovyMappingEngineImpl() {
        CompilerConfiguration config = new CompilerConfiguration();
        ImportCustomizer importCustomizer = new ImportCustomizer();
        // 导入领域对象包，方便 Groovy 脚本直接引用
        importCustomizer.addStarImports(
                "com.insurance.uw.domain.model.entity",
                "com.insurance.uw.domain.context"
        );
        config.addCompilationCustomizers(importCustomizer);
        this.classLoader = new GroovyClassLoader(
                Thread.currentThread().getContextClassLoader(), config);
    }

    @Override
    public Object invoke(String scriptId, String scriptText, String methodName, Object... args) {
        Class<GroovyObject> clazz = getScriptClass(scriptId, scriptText);
        try {
            GroovyObject instance = clazz.getDeclaredConstructor().newInstance();
            return InvokerHelper.invokeMethod(instance, methodName, args);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Groovy 脚本 [" + scriptId + "] 执行方法 [" + methodName + "] 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void evictScript(String scriptId) {
        scriptCache.invalidate(scriptId);
    }

    @SuppressWarnings("unchecked")
    private Class<GroovyObject> getScriptClass(String scriptId, String scriptText) {
        return scriptCache.get(scriptId, id -> classLoader.parseClass(scriptText));
    }

    /**
     * 获取缓存大小（用于监控）
     */
    public long getCacheSize() {
        return scriptCache.estimatedSize();
    }

}
