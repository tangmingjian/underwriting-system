package com.insurance.uw.application.feature.handler;

import com.insurance.uw.common.constants.FeatureConstants;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.entity.FeatureScript;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.service.GroovyMappingEngine;

import java.util.Collections;
import java.util.Map;

/**
 * EXPRESSION 类型处理器：基于 Groovy 脚本执行本地表达式计算。
 *
 * <p>脚本通过 {@code evaluate(ctx)} 方法接收对应聚合级别的上下文对象，
 * 返回计算结果 Map。handler 负责用 featureCode 包裹结果并按 targetId 路由。</p>
 */
public class ExpressionCalcHandler implements FeatureCalcHandler {

    private final FeatureScriptRepository scriptRepository;
    private final GroovyMappingEngine groovyEngine;

    public ExpressionCalcHandler(FeatureScriptRepository scriptRepository,
                                 GroovyMappingEngine groovyEngine) {
        this.scriptRepository = scriptRepository;
        this.groovyEngine = groovyEngine;
    }

    @Override
    public CalcType getSupportedType() {
        return CalcType.EXPRESSION;
    }

    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
        CalcConfig calcConfig = fc.getCalcConfig();
        String scriptId = calcConfig.getExpressionScriptId();
        if (scriptId == null || scriptId.isBlank()) {
            throw new IllegalArgumentException(
                    "特征 " + fc.getFeatureCode() + " 的 calc_config.expression_script_id 未配置");
        }

        // 加载脚本
        FeatureScript script = scriptRepository.findByScriptId(scriptId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "表达式脚本不存在: " + scriptId + "（特征: " + fc.getFeatureCode() + "）"));

        // 执行脚本 evaluate 方法
        Object scriptResult = groovyEngine.invoke(scriptId, script.getScriptText(), "evaluate", ctx);

        // 脚本返回 null 则跳过
        if (scriptResult == null) {
            return null;
        }

        // 确定 targetId 并包装返回值
        String targetId = resolveTargetId(ctx);
        return Collections.singletonMap(targetId,
                Collections.singletonMap(fc.getFeatureCode(), scriptResult));
    }

    /**
     * 根据上下文类型确定目标 ID。
     */
    private String resolveTargetId(Object ctx) {
        if (ctx instanceof OrderFeatureContext) {
            return FeatureConstants.ORDER_TARGET_KEY;
        } else if (ctx instanceof PolicyFeatureContext) {
            return ((PolicyFeatureContext) ctx).getPolicyId();
        } else if (ctx instanceof InsuredFeatureContext) {
            return ((InsuredFeatureContext) ctx).getInsuredId();
        } else if (ctx instanceof ApplicantFeatureContext) {
            return ((ApplicantFeatureContext) ctx).getApplicantId();
        } else {
            throw new IllegalArgumentException("不支持的上下文类型: " + ctx.getClass().getName());
        }
    }
}
