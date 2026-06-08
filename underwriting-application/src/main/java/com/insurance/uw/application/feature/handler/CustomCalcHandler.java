package com.insurance.uw.application.feature.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CUSTOM 类型处理器：根据 featureCode 路由到对应的 {@link CustomFeatureHandler} Java 实现。
 *
 * <p>通过构造注入 {@code List<CustomFeatureHandler>} 自动发现所有标注 {@code @Component} 的特征实现。</p>
 */
public class CustomCalcHandler implements FeatureCalcHandler {

    private final Map<String, CustomFeatureHandler> registry;

    public CustomCalcHandler(List<CustomFeatureHandler> handlers) {
        this.registry = handlers.stream()
                .collect(Collectors.toMap(CustomFeatureHandler::getFeatureCode, Function.identity()));
    }

    @Override
    public CalcType getSupportedType() {
        return CalcType.CUSTOM;
    }

    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
        CustomFeatureHandler handler = registry.get(fc.getFeatureCode());
        if (handler == null) {
            throw new IllegalArgumentException(
                    "未找到 featureCode=" + fc.getFeatureCode() + " 的 CustomFeatureHandler 实现");
        }
        return handler.execute(ctx, fc);
    }

    /**
     * 批量执行：同组 CUSTOM 特征逐个调用（不同 Java 实现之间无法合并）。
     */
    @Override
    public Map<String, Map<String, Object>> executeBatch(Object ctx, List<FeatureConfig> features) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (FeatureConfig fc : features) {
            results.put(fc.getFeatureCode(), execute(ctx, fc));
        }
        return results;
    }
}
