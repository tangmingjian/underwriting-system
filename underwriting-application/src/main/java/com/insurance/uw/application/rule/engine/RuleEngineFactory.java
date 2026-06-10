package com.insurance.uw.application.rule.engine;

import com.insurance.uw.common.enums.EvalType;

import java.util.Map;

/**
 * 规则引擎工厂 — 按 EvalType 分发到对应的 RuleEvaluator
 */
public class RuleEngineFactory {

    private final Map<EvalType, RuleEvaluator> evaluatorMap;

    public RuleEngineFactory(ConditionListEvaluator cle,
                             CrossDecisionTableEvaluator cdte,
                             ScorecardEvaluator se) {
        evaluatorMap = Map.of(
                EvalType.CONDITION_LIST, cle,
                EvalType.CROSS_DECISION_TABLE, cdte,
                EvalType.SCORECARD, se
        );
    }

    public boolean evaluate(EvalType evalType, Map<String, Object> features, String config) {
        EvalType type = evalType != null ? evalType : EvalType.CONDITION_LIST;
        RuleEvaluator evaluator = evaluatorMap.get(type);
        if (evaluator == null) {
            throw new IllegalArgumentException("Unknown eval type: " + type);
        }
        return evaluator.evaluate(features, config);
    }
}
