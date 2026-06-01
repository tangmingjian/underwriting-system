package com.insurance.uw.feature.core.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征计算处理器 —— 策略接口，每种 CalcType 一个实现。
 */
public interface FeatureCalcHandler {

    CalcType getSupportedType();

    Map<String, Object> execute(Object ctx, FeatureConfig fc);

    /**
     * 批量执行多个特征（合并为一次下游调用）。
     * 默认实现退化为逐个执行。需要批处理的 Handler 覆写此方法。
     *
     * @param ctx      上下文（OrderFeatureContext 或 PolicyFeatureContext）
     * @param features 同组特征列表
     * @return featureCode → (targetId → featureData)
     */
    default Map<String, Map<String, Object>> executeBatch(Object ctx, List<FeatureConfig> features) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (FeatureConfig fc : features) {
            results.put(fc.getFeatureCode(), execute(ctx, fc));
        }
        return results;
    }
}
