package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.Map;

/**
 * DATABASE_QUERY 类型处理器（桩）：直接查库获取。
 */
public class DatabaseQueryCalcHandler implements FeatureCalcHandler {

    @Override
    public CalcType getSupportedType() {
        return CalcType.DATABASE_QUERY;
    }

    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
        throw new UnsupportedOperationException("DATABASE_QUERY 类型暂未实现");
    }
}
