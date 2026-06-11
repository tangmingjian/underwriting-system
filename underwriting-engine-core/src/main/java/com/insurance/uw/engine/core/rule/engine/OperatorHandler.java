package com.insurance.uw.engine.core.rule.engine;

/**
 * 操作符比较处理器 SPI — 允许使用方自定义或覆盖操作符的比较逻辑。
 *
 * <p>实现类需标注 {@code @Component} 由 Spring 自动发现，
 * 或手动传入 {@link ConditionListEvaluator} 构造函数。</p>
 *
 * <h3>优先级</h3>
 * 自定义处理器优先于内置处理器。若多个自定义处理器支持同一操作符，
 * 排在前面的优先（按 {@code List} 顺序）。
 */
public interface OperatorHandler {

    /**
     * 是否支持该操作符。
     */
    boolean supports(String operator);

    /**
     * 对特征值和配置值进行比较。
     *
     * @param featureValue 特征实际值（可能为 null）
     * @param operator     操作符
     * @param configValue  配置期望值（可能为 List、Number、String 等）
     * @return 比较结果
     */
    boolean compare(Object featureValue, String operator, Object configValue);
}
