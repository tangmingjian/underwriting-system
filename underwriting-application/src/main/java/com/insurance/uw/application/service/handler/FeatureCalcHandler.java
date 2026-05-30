package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.Map;

/**
 * 特征计算处理器 —— 策略接口，每种 CalcType 一个实现。
 */
public interface FeatureCalcHandler {

    CalcType getSupportedType();

    Map<String, Object> execute(Object ctx, FeatureConfig fc);
}
