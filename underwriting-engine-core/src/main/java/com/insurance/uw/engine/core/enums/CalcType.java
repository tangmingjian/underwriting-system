package com.insurance.uw.engine.core.enums;

/**
 * 计算类型
 */
public enum CalcType {
    PARAM_MAPPING,    // 直接参数映射（同步，纯 CPU）
    EXPRESSION,       // Groovy 表达式脚本
    EXTERNAL_API,     // 下游 HTTP 接口
    DATABASE_QUERY,   // 直接 DB 查询（stub）
    COMPOSITE,        // 组合特征（stub）
    CUSTOM            // 自定义 Java 实现
}
