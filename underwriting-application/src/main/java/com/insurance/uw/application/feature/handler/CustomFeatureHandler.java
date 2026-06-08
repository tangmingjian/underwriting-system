package com.insurance.uw.application.feature.handler;

import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.Map;

/**
 * 自定义特征处理器接口 —— 每个实现 = 一个特征的完整 Java 实现。
 *
 * <p>与 {@link FeatureCalcHandler} 独立，避免被 Spring {@code List<FeatureCalcHandler>} 注入污染。
 * 实现类需标注 {@code @Component} 由 Spring 自动发现。</p>
 *
 * <p>返回值遵循 {@link FeatureCalcHandler} 的 Result Key Convention。</p>
 */
public interface CustomFeatureHandler {

    /** 绑定的特征代码，与 FeatureConfig.featureCode 匹配 */
    String getFeatureCode();

    /** 执行特征计算，返回 Map&lt;targetKey, featureValue&gt; */
    Map<String, Object> execute(Object ctx, FeatureConfig fc);
}
