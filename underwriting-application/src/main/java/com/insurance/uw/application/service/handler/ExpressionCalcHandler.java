package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.Map;

/**
 * EXPRESSION 类型处理器（桩）：基于 SpEL 表达式计算。
 */
public class ExpressionCalcHandler implements FeatureCalcHandler {

    @Override
    public CalcType getSupportedType() {
        return CalcType.EXPRESSION;
    }

    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
        throw new UnsupportedOperationException("EXPRESSION 类型暂未实现");
    }
}
