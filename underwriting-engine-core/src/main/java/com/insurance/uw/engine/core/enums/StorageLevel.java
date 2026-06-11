package com.insurance.uw.engine.core.enums;

/**
 * 存储层级（特征结果最终写入的上下文层级）。
 */
public enum StorageLevel {
    INSURED,
    APPLICANT,
    POLICY,
    ORDER

    ;

    public int depth() {
        return ordinal();
    }
}
