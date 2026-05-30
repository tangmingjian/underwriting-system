package com.insurance.uw.common.enums;

/**
 * 特征计算类型 —— 决定 calc_config 的存储结构
 */
public enum CalcType {

    /** 参数映射：直接从请求参数中取值 */
    PARAM_MAPPING,

    /** 表达式计算：基于 SpEL 表达式计算 */
    EXPRESSION,

    /** 外部 API 调用：调用下游接口获取 */
    EXTERNAL_API,

    /** 数据库查询：直接查库获取 */
    DATABASE_QUERY,

    /** 复合计算：组合多个子特征 */
    COMPOSITE

}
