package com.insurance.uw.engine.core.handler;

import com.insurance.uw.engine.core.constants.FeatureConstants;
import com.insurance.uw.engine.core.context.ContextNode;
import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.model.entity.FeatureScript;
import com.insurance.uw.engine.core.model.valueobject.CalcConfig;
import com.insurance.uw.engine.core.repository.FeatureScriptRepository;
import com.insurance.uw.engine.core.service.GroovyMappingEngine;

import java.util.Collections;
import java.util.Map;

/**
 * EXPRESSION 类型处理器：基于 Groovy 脚本执行本地表达式计算。
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

        FeatureScript script = scriptRepository.findByScriptId(scriptId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "表达式脚本不存在: " + scriptId + "（特征: " + fc.getFeatureCode() + "）"));

        Object scriptResult = groovyEngine.invoke(scriptId, script.getScriptText(), "evaluate", ctx);

        if (scriptResult == null) {
            return null;
        }

        // 使用 ContextNode.getNodeId() 获取目标 ID（替代 instanceof 判断）
        String targetId = resolveTargetId(ctx);
        return Collections.singletonMap(targetId,
                Collections.singletonMap(fc.getFeatureCode(), scriptResult));
    }

    /**
     * 根据 ContextNode 确定目标 ID（替代原来的 4 个 instanceof 判断）。
     * 若非 ContextNode 实例，尝试反射调用 getNodeId() 或退化为 ORDER_TARGET_KEY。
     */
    private String resolveTargetId(Object ctx) {
        if (ctx instanceof ContextNode) {
            return ((ContextNode) ctx).getNodeId();
        }
        // fallback: 对非 ContextNode 的上下文（理论上不应发生），返回 ORDER_TARGET_KEY
        return FeatureConstants.ORDER_TARGET_KEY;
    }
}
