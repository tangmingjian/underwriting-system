package com.insurance.uw.engine.core.service;

/**
 * Groovy 脚本映射引擎接口
 */
public interface GroovyMappingEngine {

    /**
     * 调用 Groovy 脚本中的指定方法
     *
     * @param scriptId   脚本唯一标识（用于缓存key）
     * @param scriptText 脚本全文
     * @param methodName 方法名（如 buildRequest / extractFeatures）
     * @param args       方法参数
     * @return 方法返回值
     */
    Object invoke(String scriptId, String scriptText, String methodName, Object... args);

    /**
     * 清除指定脚本的缓存
     */
    void evictScript(String scriptId);

    /**
     * 清除所有脚本的编译缓存
     */
    void evictAll();

}
