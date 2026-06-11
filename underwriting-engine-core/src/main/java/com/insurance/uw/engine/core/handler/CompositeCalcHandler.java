package com.insurance.uw.engine.core.handler;

import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;

import java.util.Map;

/**
 * COMPOSITE 类型处理器（桩）：组合多个子特征。
 */
public class CompositeCalcHandler implements FeatureCalcHandler {

    @Override
    public CalcType getSupportedType() {
        return CalcType.COMPOSITE;
    }

    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
        throw new UnsupportedOperationException("COMPOSITE 类型暂未实现");
    }
}
