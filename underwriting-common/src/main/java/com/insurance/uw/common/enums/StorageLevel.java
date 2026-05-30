package com.insurance.uw.common.enums;

/**
 * 特征结果存储级别
 */
public enum StorageLevel {

    /** 存储到被保人上下文 */
    INSURED,

    /** 存储到投保人上下文 */
    APPLICANT,

    /** 存储到保单上下文 */
    POLICY,

    /** 存储到订单上下文 */
    ORDER

}
