package com.insurance.uw.engine.core.enums;

/**
 * 聚合层级（特征按此层级分组执行）。
 * 数值越大表示层级越高（聚合范围越大）。
 */
public enum AggregationLevel {
    INSURED,    // 被保人级
    APPLICANT,  // 投保人级
    POLICY,     // 保单级
    ORDER       // 订单级

    ;

    /**
     * 层级深度：数值越大层级越高（聚合范围越大）。
     * ORDER(3) > POLICY(2) > APPLICANT(1) > INSURED(0)
     */
    public int depth() {
        return ordinal();
    }

    /**
     * 是否允许作为指定聚合层级特征的存储目标。
     * 只允许向下或同级存储（不允许向上存储）。
     */
    public boolean canStoreFrom(AggregationLevel aggLevel) {
        return depth() <= aggLevel.depth();
    }
}
