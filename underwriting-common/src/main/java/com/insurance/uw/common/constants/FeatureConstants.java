package com.insurance.uw.common.constants;

/**
 * 特征计算相关的隐式契约常量
 */
public final class FeatureConstants {

    private FeatureConstants() {}

    /** ParamMappingCalcHandler 中 ORDER 级聚合结果的目标 key，表示将结果写入订单级特征 */
    public static final String ORDER_TARGET_KEY = "__ORDER__";

    /** ParamMappingCalcHandler 中 INSURED/APPLICANT 级聚合结果的目标 key，表示将结果写入自身上下文 */
    public static final String SELF_TARGET_KEY = "_self_";
}
