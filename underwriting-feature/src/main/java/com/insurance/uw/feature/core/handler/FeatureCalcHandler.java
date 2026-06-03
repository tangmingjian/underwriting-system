package com.insurance.uw.feature.core.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征计算处理器 —— 策略接口，每种 CalcType 一个实现。
 *
 * <h3>Result Key Convention</h3>
 * Handler implementations MUST use the following standardized keys in their
 * result maps to ensure correct routing by {@code FeatureResultDispatcher}:
 *
 * <table>
 *   <tr><th>Key</th><th>Meaning</th><th>Used When</th></tr>
 *   <tr><td>{@code __ORDER__}</td><td>Order-scoped result</td><td>entityType=order at ORDER/POLICY level</td></tr>
 *   <tr><td>{@code _self_}</td><td>Self-context (key ignored by store)</td><td>INSURED/APPLICANT level</td></tr>
 *   <tr><td>{@code {insuredId}}</td><td>Result for specific insured</td><td>entityType=insured at ORDER/POLICY level</td></tr>
 *   <tr><td>{@code {policyId}}</td><td>Result for specific policy</td><td>entityType=policy/applicant at ORDER level</td></tr>
 * </table>
 */
public interface FeatureCalcHandler {

    CalcType getSupportedType();

    /**
     * Execute feature calculation.
     *
     * @param ctx computation context (OrderFeatureContext / PolicyFeatureContext /
     *            InsuredFeatureContext / ApplicantFeatureContext)
     * @param fc  feature configuration
     * @return Map&lt;targetKey, featureValue&gt; where targetKey follows the
     *         {@linkplain FeatureCalcHandler result key convention}
     */
    Map<String, Object> execute(Object ctx, FeatureConfig fc);

    /**
     * 批量执行多个特征（合并为一次下游调用）。
     * 默认实现退化为逐个执行。需要批处理的 Handler 覆写此方法。
     *
     * @param ctx      上下文（OrderFeatureContext 或 PolicyFeatureContext）
     * @param features 同组特征列表
     * @return featureCode → (targetId → featureData)
     */
    default Map<String, Map<String, Object>> executeBatch(Object ctx, List<FeatureConfig> features) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (FeatureConfig fc : features) {
            results.put(fc.getFeatureCode(), execute(ctx, fc));
        }
        return results;
    }
}
