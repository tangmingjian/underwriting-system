package com.insurance.uw.common.enums;

/**
 * 特征分类
 */
public enum FeatureCategory {

    /** 原子特征：直接取值，无需计算 */
    ATOMIC,

    /** 衍生特征：基于其他特征计算得出 */
    DERIVED,

    /** 复合特征：组合多个特征 */
    COMPOSITE

}
