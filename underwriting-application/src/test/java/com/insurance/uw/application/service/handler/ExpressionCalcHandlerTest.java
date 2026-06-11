package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.constants.FeatureConstants;
import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.Applicant;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.model.entity.FeatureScript;
import com.insurance.uw.domain.model.entity.Insured;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.domain.model.entity.Policy;
import com.insurance.uw.domain.model.entity.Product;
import com.insurance.uw.engine.core.model.valueobject.CalcConfig;
import com.insurance.uw.engine.core.repository.FeatureScriptRepository;
import com.insurance.uw.engine.core.service.GroovyMappingEngine;
import com.insurance.uw.engine.core.handler.ExpressionCalcHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("ExpressionCalcHandler - 表达式计算处理器")
@ExtendWith(MockitoExtension.class)
class ExpressionCalcHandlerTest {

    @Mock
    private FeatureScriptRepository scriptRepository;

    @Mock
    private GroovyMappingEngine groovyEngine;

    private ExpressionCalcHandler handler;

    private OrderFeatureContext orderCtx;
    private PolicyFeatureContext polCtx;
    private InsuredFeatureContext insCtx;
    private ApplicantFeatureContext appCtx;

    @BeforeEach
    void setUp() {
        handler = new ExpressionCalcHandler(scriptRepository, groovyEngine);

        Product product = new Product("PROD001", "测试产品");
        Applicant applicant = new Applicant("APP001", "投保人张三", 35, "M");
        Insured insured = new Insured("INS001", "张三", 35, "M");

        Policy policy = new Policy("POL001", product, applicant, List.of(insured));
        Order order = new Order("ORD001", "ONLINE", null, List.of(policy));

        orderCtx = new OrderFeatureContext(order);
        polCtx = orderCtx.getPolicies().get(0);
        insCtx = polCtx.getInsureds().get(0);
        appCtx = polCtx.getApplicantCtx();
    }

    private FeatureConfig fc(String featureCode, String expressionScriptId) {
        FeatureConfig config = new FeatureConfig();
        config.setFeatureCode(featureCode);
        CalcConfig calcConfig = new CalcConfig();
        calcConfig.setExpressionScriptId(expressionScriptId);
        config.setCalcConfig(calcConfig);
        return config;
    }

    @Nested
    @DisplayName("getSupportedType")
    class SupportedType {

        @Test
        @DisplayName("返回 EXPRESSION")
        void returnsExpression() {
            assertThat(handler.getSupportedType()).isEqualTo(CalcType.EXPRESSION);
        }
    }

    @Nested
    @DisplayName("INSURED 级别计算")
    class InsuredLevel {

        @Test
        @DisplayName("脚本收到 InsuredFeatureContext，正确返回结果")
        void evaluateWithInsuredContext() {
            FeatureConfig fc = fc("ins.age", "computeAge");
            FeatureScript script = new FeatureScript();
            script.setScriptId("computeAge");
            script.setScriptText("def evaluate(ctx) { return [age: 35] }");
            Map<String, Object> scriptResult = Collections.singletonMap("age", 35);

            when(scriptRepository.findByScriptId("computeAge"))
                    .thenReturn(Optional.of(script));
            when(groovyEngine.invoke(eq("computeAge"), any(), eq("evaluate"), eq(insCtx)))
                    .thenReturn(scriptResult);

            Map<String, Object> result = handler.execute(insCtx, fc);

            assertThat(result).isNotNull();
            assertThat(result).containsKey("INS001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureData = (Map<String, Object>) result.get("INS001");
            assertThat(featureData).containsEntry("ins.age", scriptResult);
        }
    }

    @Nested
    @DisplayName("POLICY 级别计算")
    class PolicyLevel {

        @Test
        @DisplayName("脚本收到 PolicyFeatureContext，正确返回结果")
        void evaluateWithPolicyContext() {
            FeatureConfig fc = fc("pol.premiumRate", "calcPremiumRate");
            FeatureScript script = new FeatureScript();
            script.setScriptId("calcPremiumRate");
            script.setScriptText("def evaluate(ctx) { return [rate: 0.03] }");
            Map<String, Object> scriptResult = Collections.singletonMap("rate", 0.03);

            when(scriptRepository.findByScriptId("calcPremiumRate"))
                    .thenReturn(Optional.of(script));
            when(groovyEngine.invoke(eq("calcPremiumRate"), any(), eq("evaluate"), eq(polCtx)))
                    .thenReturn(scriptResult);

            Map<String, Object> result = handler.execute(polCtx, fc);

            assertThat(result).isNotNull();
            assertThat(result).containsKey("POL001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureData = (Map<String, Object>) result.get("POL001");
            assertThat(featureData).containsEntry("pol.premiumRate", scriptResult);
        }
    }

    @Nested
    @DisplayName("ORDER 级别计算")
    class OrderLevel {

        @Test
        @DisplayName("targetId 为 __ORDER__")
        void targetIdIsOrderKey() {
            FeatureConfig fc = fc("ord.totalPremium", "calcTotalPremium");
            FeatureScript script = new FeatureScript();
            script.setScriptId("calcTotalPremium");
            script.setScriptText("def evaluate(ctx) { return [total: 10000] }");
            Map<String, Object> scriptResult = Collections.singletonMap("total", 10000);

            when(scriptRepository.findByScriptId("calcTotalPremium"))
                    .thenReturn(Optional.of(script));
            when(groovyEngine.invoke(eq("calcTotalPremium"), any(), eq("evaluate"), eq(orderCtx)))
                    .thenReturn(scriptResult);

            Map<String, Object> result = handler.execute(orderCtx, fc);

            assertThat(result).isNotNull();
            assertThat(result).containsKey(FeatureConstants.ORDER_TARGET_KEY);
        }
    }

    @Nested
    @DisplayName("APPLICANT 级别计算")
    class ApplicantLevel {

        @Test
        @DisplayName("targetId 为 applicantId")
        void targetIdIsApplicantId() {
            FeatureConfig fc = fc("app.creditScore", "calcCreditScore");
            FeatureScript script = new FeatureScript();
            script.setScriptId("calcCreditScore");
            script.setScriptText("def evaluate(ctx) { return [score: 700] }");
            Map<String, Object> scriptResult = Collections.singletonMap("score", 700);

            when(scriptRepository.findByScriptId("calcCreditScore"))
                    .thenReturn(Optional.of(script));
            when(groovyEngine.invoke(eq("calcCreditScore"), any(), eq("evaluate"), eq(appCtx)))
                    .thenReturn(scriptResult);

            Map<String, Object> result = handler.execute(appCtx, fc);

            assertThat(result).isNotNull();
            assertThat(result).containsKey("APP001");
        }
    }

    @Nested
    @DisplayName("异常场景")
    class ErrorScenarios {

        @Test
        @DisplayName("calc_config 缺少 expression_script_id → 抛 IllegalArgumentException")
        void missingScriptId() {
            FeatureConfig fc = fc("ins.age", null);

            assertThatThrownBy(() -> handler.execute(insCtx, fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expression_script_id 未配置");
        }

        @Test
        @DisplayName("expression_script_id 为空白 → 抛 IllegalArgumentException")
        void blankScriptId() {
            FeatureConfig fc = fc("ins.age", "  ");

            assertThatThrownBy(() -> handler.execute(insCtx, fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expression_script_id 未配置");
        }

        @Test
        @DisplayName("脚本不存在 → 抛 IllegalArgumentException")
        void scriptNotFound() {
            FeatureConfig fc = fc("ins.age", "nonExistentScript");

            when(scriptRepository.findByScriptId("nonExistentScript"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.execute(insCtx, fc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("表达式脚本不存在")
                    .hasMessageContaining("nonExistentScript");
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("脚本返回 null → 返回 null，不抛异常")
        void scriptReturnsNull() {
            FeatureConfig fc = fc("ins.age", "computeAge");
            FeatureScript script = new FeatureScript();
            script.setScriptId("computeAge");
            script.setScriptText("def evaluate(ctx) { return null }");

            when(scriptRepository.findByScriptId("computeAge"))
                    .thenReturn(Optional.of(script));
            when(groovyEngine.invoke(eq("computeAge"), any(), eq("evaluate"), eq(insCtx)))
                    .thenReturn(null);

            Map<String, Object> result = handler.execute(insCtx, fc);

            assertThat(result).isNull();
        }
    }
}
