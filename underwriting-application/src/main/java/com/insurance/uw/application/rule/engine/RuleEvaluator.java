package com.insurance.uw.application.rule.engine;

import java.util.Map;

/**
 * 规则评估器接口
 */
public interface RuleEvaluator {

    boolean evaluate(Map<String, Object> features, String config);
}
