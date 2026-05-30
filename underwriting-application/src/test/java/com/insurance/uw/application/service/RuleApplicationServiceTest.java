package com.insurance.uw.application.service;

import com.insurance.uw.common.enums.RuleType;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.model.entity.*;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("RuleApplicationService - 规则应用服务")
@ExtendWith(MockitoExtension.class)
class RuleApplicationServiceTest {

    @Mock
    private UnderwritingRuleRepository ruleRepository;

    private RuleApplicationService service;
    private OrderFeatureContext orderCtx;

    @BeforeEach
    void setUp() {
        service = new RuleApplicationService(ruleRepository);

        Product product = new Product("PROD001", "测试产品");
        Applicant applicant = new Applicant("APP001", "张三", 35, "M");
        Insured insured1 = new Insured("INS001", "张三", 35, "M");
        Insured insured2 = new Insured("INS002", "李四", 28, "F");

        Policy policy = new Policy("POL001", product, applicant, List.of(insured1, insured2));
        Order order = new Order("ORD001", "ONLINE", null, List.of(policy));

        orderCtx = new OrderFeatureContext(order);
    }

    private UnderwritingRule createRule(String code, String name, RuleType type, String expr) {
        UnderwritingRule rule = new UnderwritingRule();
        rule.setRuleCode(code);
        rule.setRuleName(name);
        rule.setRuleType(type);
        rule.setExpression(expr);
        rule.setPriority(10);
        rule.setStatus(1);
        return rule;
    }

    @Nested
    @DisplayName("基础 CRUD")
    class Crud {

        @Test
        @DisplayName("listAll → 返回所有启用规则")
        void listAll() {
            List<UnderwritingRule> rules = List.of(
                    createRule("R1", "规则1", RuleType.INSURED, "#root['age'] > 18")
            );
            when(ruleRepository.findAllEnabled()).thenReturn(rules);

            List<UnderwritingRule> result = service.listAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRuleCode()).isEqualTo("R1");
        }
    }

    @Nested
    @DisplayName("evaluate - 核保评估")
    class Evaluate {

        @Test
        @DisplayName("无规则 → 返回空结果")
        void noRules() {
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());

            var results = service.evaluate(orderCtx);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("INSURED 规则 → 对每个被保人评估")
        void insuredRule() {
            // Set features so the SpEL expression can evaluate
            orderCtx.getAllInsuredContexts().forEach(ic ->
                    ic.getAcquiredFeatures().put("age", 35));

            UnderwritingRule rule = createRule("R1", "年龄>=30", RuleType.INSURED,
                    "#root['age'] >= 30");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            assertThat(results).hasSize(2); // 2 insureds
            assertThat(results).allMatch(r -> r.getLevel().equals("INSURED"));
            assertThat(results).allMatch(r -> r.getRuleCode().equals("R1"));
            assertThat(results).allMatch(RuleApplicationService.UnderwritingResult::isPassed);
            assertThat(results).extracting(RuleApplicationService.UnderwritingResult::getTargetId)
                    .containsExactlyInAnyOrder("INS001", "INS002");
        }

        @Test
        @DisplayName("INSURED 规则 → 部分通过部分不通过")
        void insuredPartialPass() {
            orderCtx.findInsuredCtx("INS001").getAcquiredFeatures().put("age", 35);
            orderCtx.findInsuredCtx("INS002").getAcquiredFeatures().put("age", 25);

            UnderwritingRule rule = createRule("R1", "年龄>=30", RuleType.INSURED,
                    "#root['age'] >= 30"); // 30 is the threshold
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            assertThat(results).hasSize(2);
            var result1 = results.stream().filter(r -> r.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(result1.isPassed()).isTrue();

            // INS002 has age 25, which is < 30, so should NOT pass
            var result2 = results.stream().filter(r -> r.getTargetId().equals("INS002")).findFirst().orElseThrow();
            assertThat(result2.isPassed()).isFalse();
        }

        @Test
        @DisplayName("APPLICANT 规则 → 对每个投保人评估")
        void applicantRule() {
            orderCtx.getPolicies().get(0).getApplicantCtx().getFeatures().put("credit", 80);

            UnderwritingRule rule = createRule("R2", "信用>=70", RuleType.APPLICANT,
                    "#root['credit'] >= 70");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getLevel()).isEqualTo("APPLICANT");
            assertThat(results.get(0).getTargetId()).isEqualTo("APP001");
            assertThat(results.get(0).isPassed()).isTrue();
        }

        @Test
        @DisplayName("POLICY 规则 → 对每个保单评估")
        void policyRule() {
            orderCtx.getPolicies().get(0).getPolicyFeatures().put("premium", 5000);

            UnderwritingRule rule = createRule("R3", "保费<10000", RuleType.POLICY,
                    "#root['premium'] < 10000");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getLevel()).isEqualTo("POLICY");
            assertThat(results.get(0).getTargetId()).isEqualTo("POL001");
            assertThat(results.get(0).isPassed()).isTrue();
        }

        @Test
        @DisplayName("特征收集覆盖 — INSURED 规则使用来自各层级的特征")
        void featureOverridesForInsured() {
            // Order feature (lowest priority)
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            // Policy feature
            orderCtx.getPolicies().get(0).getPolicyFeatures().put("channel", "OFFLINE");
            // Insured feature (highest priority - should win)
            orderCtx.findInsuredCtx("INS001").getAcquiredFeatures().put("channel", "OFFLINE");

            // Rule evaluates the collected feature: should see "OFFLINE" (insured level wins)
            UnderwritingRule rule = createRule("R_CH", "渠道检查", RuleType.INSURED,
                    "#root['channel'] == 'OFFLINE'");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).isPassed()).isTrue();
        }

        @Test
        @DisplayName("复杂 SpEL 表达式 → 正确评估")
        void complexExpression() {
            orderCtx.findInsuredCtx("INS001").getAcquiredFeatures().put("age", 35);
            orderCtx.findInsuredCtx("INS001").getAcquiredFeatures().put("score", 85);

            UnderwritingRule rule = createRule("R_COMPLEX", "复合条件",
                    RuleType.INSURED, "#root['age'] > 30 and #root['score'] > 60");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            // INS001: age=35>30, score=85>60 → true
            var r1 = results.stream().filter(r -> r.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r1.isPassed()).isTrue();

            // INS002: has no features → expression returns false (null != true)
            var r2 = results.stream().filter(r -> r.getTargetId().equals("INS002")).findFirst().orElseThrow();
            assertThat(r2.isPassed()).isFalse();
        }

        @Test
        @DisplayName("优先级排序 → 按 priority 升序排列")
        void prioritySorting() {
            UnderwritingRule rule2 = createRule("R2", "规则2", RuleType.INSURED, "true");
            rule2.setPriority(5);

            UnderwritingRule rule1 = createRule("R1", "规则1", RuleType.INSURED, "true");
            rule1.setPriority(1);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule2, rule1));

            var results = service.evaluate(orderCtx);

            // R1 (priority 1) should be first, R2 (priority 5) second
            // Each INSURED rule produces 2 results (one per insured)
            assertThat(results.get(0).getRuleCode()).isEqualTo("R1");
            assertThat(results.get(1).getRuleCode()).isEqualTo("R1");
            assertThat(results.get(2).getRuleCode()).isEqualTo("R2");
            assertThat(results.get(3).getRuleCode()).isEqualTo("R2");
        }

        @Test
        @DisplayName("priority 为 null → 视为 0")
        void nullPriority() {
            UnderwritingRule rule = createRule("R1", "规则1", RuleType.INSURED, "true");
            rule.setPriority(null);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            // Should not throw NPE
            var results = service.evaluate(orderCtx);
            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("表达式返回 null → 视为 false（不通过）")
        void nullExpressionResult() {
            UnderwritingRule rule = createRule("R1", "规则1", RuleType.INSURED,
                    "#root['nonexistent']"); // Returns null
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(orderCtx);

            assertThat(results).allMatch(r -> !r.isPassed());
        }
    }

    @Nested
    @DisplayName("UnderwritingResult DTO")
    class ResultDto {

        @Test
        @DisplayName("构造正确 → getter 返回正确值")
        void correctConstruction() {
            var result = new RuleApplicationService.UnderwritingResult(
                    "INSURED", "INS001", "张三", "R1", "年龄检查", true);

            assertThat(result.getLevel()).isEqualTo("INSURED");
            assertThat(result.getTargetId()).isEqualTo("INS001");
            assertThat(result.getTargetName()).isEqualTo("张三");
            assertThat(result.getRuleCode()).isEqualTo("R1");
            assertThat(result.getRuleName()).isEqualTo("年龄检查");
            assertThat(result.isPassed()).isTrue();
        }
    }
}
