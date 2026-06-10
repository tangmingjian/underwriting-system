package com.insurance.uw.common.enums;

/**
 * 规则评估类型 — 决定用哪种评估算法
 */
public enum EvalType {

    /** 条件列表评估 */
    CONDITION_LIST,

    /** 交叉决策表评估 */
    CROSS_DECISION_TABLE,

    /** 评分卡评估 */
    SCORECARD

}
