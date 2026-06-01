package com.insurance.uw.common.enums;

/**
 * 特征请求聚合级别
 */
public enum AggregationLevel {

    /** 订单级聚合：整个订单的所有被保人聚合为一次请求 */
    ORDER,

    /** 投保单级聚合：按投保单维度分别请求 */
    POLICY,

    /** 被保人级：每个被保人独立执行一次 */
    INSURED,

    /** 投保人级：每个投保人独立执行一次 */
    APPLICANT

}
