package com.insurance.uw.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.application.rule.WordingResolver;
import com.insurance.uw.application.rule.engine.ConditionListEvaluator;
import com.insurance.uw.application.rule.engine.CrossDecisionTableEvaluator;
import com.insurance.uw.application.rule.engine.RuleEngineFactory;
import com.insurance.uw.application.rule.engine.ScorecardEvaluator;
import com.insurance.uw.common.enums.EvalType;
import com.insurance.uw.common.enums.RuleType;
import com.insurance.uw.domain.model.entity.*;
import com.insurance.uw.domain.repository.CrossDecisionTableRepository;
import com.insurance.uw.domain.repository.ScorecardConfigRepository;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("RuleApplicationService - 规则应用服务")
@ExtendWith(MockitoExtension.class)
class RuleApplicationServiceTest {

    @Mock
    private UnderwritingRuleRepository ruleRepository;

    @Mock
    private CrossDecisionTableRepository cdtRepository;

    @Mock
    private ScorecardConfigRepository scRepository;

    private RuleApplicationService service;
    private Order order;
    private FeatureExtractionResult featResult;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ConditionListEvaluator cle = new ConditionListEvaluator(objectMapper);
        CrossDecisionTableEvaluator cdte = new CrossDecisionTableEvaluator(cdtRepository, new ObjectMapper());
        ScorecardEvaluator se = new ScorecardEvaluator(scRepository, new ObjectMapper());
        RuleEngineFactory factory = new RuleEngineFactory(cle, cdte, se);
        WordingResolver wordingResolver = new WordingResolver(objectMapper);
        service = new RuleApplicationService(ruleRepository, factory, wordingResolver);

        Product product = new Product("PROD001", "测试产品");
        Applicant applicant = new Applicant("APP001", "张三", 35, "M");
        Insured insured1 = new Insured("INS001", "张三", 35, "M");
        Insured insured2 = new Insured("INS002", "李四", 28, "F");

        Policy policy = new Policy("POL001", product, applicant, List.of(insured1, insured2));
        order = new Order("ORD001", "ONLINE", null, List.of(policy));

        featResult = new FeatureExtractionResult();
    }

    private UnderwritingRule createRule(String code, String name, RuleType type, String expr) {
        UnderwritingRule rule = new UnderwritingRule();
        rule.setRuleCode(code);
        rule.setRuleName(name);
        rule.setRuleType(type);
        rule.setEvalType(EvalType.CONDITION_LIST);
        rule.setExpression(expr);
        rule.setPriority(10);
        rule.setStatus(1);
        return rule;
    }

    @Nested
    @DisplayName("buildExtractionRequest - 特征请求构建")
    class BuildExtractionRequest {

        @Test
        @DisplayName("规则匹配产品 → 推导特征码和映射")
        void rulesMatchProduct() {
            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode("PROD001");
            rule.setFeatureCodes("RISK_SCORE,AGE");
            rule.setStatus(1);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            FeatureExtractionRequest req = service.buildExtractionRequest(order);

            assertThat(req.getFeatureCodes()).containsExactlyInAnyOrder("RISK_SCORE", "AGE");
            assertThat(req.getPolicyInsuredFeatureMap()).containsKeys("POL001");
            assertThat(req.getPolicyApplicantFeatureMap()).containsKeys("POL001");
        }

        @Test
        @DisplayName("产品码不匹配 → 规则被跳过")
        void productCodeMismatch() {
            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode("OTHER_PROD");
            rule.setFeatureCodes("RISK_SCORE");
            rule.setStatus(1);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            FeatureExtractionRequest req = service.buildExtractionRequest(order);

            assertThat(req.getFeatureCodes()).isEmpty();
        }

        @Test
        @DisplayName("product_code 为 null → 适用于所有产品（向后兼容）")
        void nullProductCodeAppliesToAll() {
            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode(null);
            rule.setFeatureCodes("AGE");
            rule.setStatus(1);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            FeatureExtractionRequest req = service.buildExtractionRequest(order);

            assertThat(req.getFeatureCodes()).contains("AGE");
        }
    }

    @Nested
    @DisplayName("基础 CRUD")
    class Crud {

        @Test
        @DisplayName("listAll → 返回所有启用规则")
        void listAll() {
            List<UnderwritingRule> rules = List.of(
                    createRule("R1", "规则1", RuleType.INSURED,
                            "{\"logic\":\"AND\",\"items\":[{\"feature\":\"age\",\"operator\":\"GT\",\"value\":18}]}")
            );
            when(ruleRepository.findAllEnabled()).thenReturn(rules);

            List<UnderwritingRule> result = service.listAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRuleCode()).isEqualTo("R1");
        }
    }

    @Nested
    @DisplayName("evaluate - 核保评估（FeatureExtractionResult）")
    class Evaluate {

        @Test
        @DisplayName("无规则 → 返回空结果")
        void noRules() {
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());

            var results = service.evaluate(order, featResult);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("INSURED 规则 → 对每个被保人评估")
        void insuredRule() {
            featResult.putInsuredFeature("POL001", "INS001", Map.of("age", 35));
            featResult.putInsuredFeature("POL001", "INS002", Map.of("age", 28));

            UnderwritingRule rule = createRule("R1", "年龄>=30", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":30}]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            assertThat(results).hasSize(2);
            assertThat(results).allMatch(r -> r.getLevel().equals("INSURED"));
            assertThat(results).allMatch(r -> r.getRuleCode().equals("R1"));
        }

        @Test
        @DisplayName("INSURED 规则 → 部分通过部分不通过")
        void insuredPartialPass() {
            featResult.putInsuredFeature("POL001", "INS001", Map.of("age", 35));
            featResult.putInsuredFeature("POL001", "INS002", Map.of("age", 25));

            UnderwritingRule rule = createRule("R1", "年龄>=30", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":30}]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            assertThat(results).hasSize(2);
            var r1 = results.stream().filter(r -> r.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r1.isPassed()).isTrue();
            var r2 = results.stream().filter(r -> r.getTargetId().equals("INS002")).findFirst().orElseThrow();
            assertThat(r2.isPassed()).isFalse();
        }

        @Test
        @DisplayName("APPLICANT 规则 → 对每个投保人评估")
        void applicantRule() {
            featResult.putApplicantFeature("POL001", "APP001", Map.of("credit", 80));

            UnderwritingRule rule = createRule("R2", "信用>=70", RuleType.APPLICANT,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"credit\",\"operator\":\"GTE\",\"value\":70}]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getLevel()).isEqualTo("APPLICANT");
            assertThat(results.get(0).getTargetId()).isEqualTo("APP001");
            assertThat(results.get(0).isPassed()).isTrue();
        }

        @Test
        @DisplayName("POLICY 规则 → 对每个保单评估")
        void policyRule() {
            featResult.getPolicyFeatures().put("POL001", Map.of("premium", 5000));

            UnderwritingRule rule = createRule("R3", "保费<10000", RuleType.POLICY,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"premium\",\"operator\":\"LT\",\"value\":10000}]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getLevel()).isEqualTo("POLICY");
            assertThat(results.get(0).getTargetId()).isEqualTo("POL001");
            assertThat(results.get(0).isPassed()).isTrue();
        }

        @Test
        @DisplayName("特征收集覆盖 — INSURED 特征优先级最高")
        void featureOverridesForInsured() {
            featResult.getOrderFeatures().put("channel", "ONLINE");
            featResult.getPolicyFeatures().put("POL001", new HashMap<>(Map.of("channel", "OFFLINE")));
            featResult.putInsuredFeature("POL001", "INS001", Map.of("channel", "OFFLINE"));

            UnderwritingRule rule = createRule("R_CH", "渠道检查", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"channel\",\"operator\":\"EQ\",\"value\":\"OFFLINE\"}]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            assertThat(results).hasSize(2);
            var r1 = results.stream().filter(r -> r.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r1.isPassed()).isTrue();
        }

        @Test
        @DisplayName("复杂条件 → 正确评估（AND）")
        void complexExpression() {
            featResult.putInsuredFeature("POL001", "INS001", Map.of("age", 35, "score", 85));

            UnderwritingRule rule = createRule("R_COMPLEX", "复合条件",
                    RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":["
                            + "{\"feature\":\"age\",\"operator\":\"GT\",\"value\":30},"
                            + "{\"feature\":\"score\",\"operator\":\"GT\",\"value\":60}"
                            + "]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            var r1 = results.stream().filter(r -> r.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r1.isPassed()).isTrue();
        }

        @Test
        @DisplayName("优先级排序 → 按 priority 升序排列")
        void prioritySorting() {
            UnderwritingRule rule2 = createRule("R2", "规则2", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[]}");
            rule2.setPriority(5);

            UnderwritingRule rule1 = createRule("R1", "规则1", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[]}");
            rule1.setPriority(1);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule2, rule1));

            var results = service.evaluate(order, featResult);

            assertThat(results.get(0).getRuleCode()).isEqualTo("R1");
            assertThat(results.get(1).getRuleCode()).isEqualTo("R1");
            assertThat(results.get(2).getRuleCode()).isEqualTo("R2");
            assertThat(results.get(3).getRuleCode()).isEqualTo("R2");
        }

        @Test
        @DisplayName("priority 为 null → 视为 0")
        void nullPriority() {
            UnderwritingRule rule = createRule("R1", "规则1", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[]}");
            rule.setPriority(null);

            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);
            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("表达式返回 false → 视为不通过")
        void falseExpressionResult() {
            UnderwritingRule rule = createRule("R1", "规则1", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"nonexistent\",\"operator\":\"IS_NOT_NULL\"}]}");
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

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

    @Nested
    @DisplayName("History - 规则历史查询")
    class History {

        @Test
        @DisplayName("查询规则历史 → 返回按版本降序的多版本记录")
        void getHistory() {
            UnderwritingRuleHistory v2 = new UnderwritingRuleHistory();
            v2.setVersion(2);
            v2.setRuleCode("RULE_001");
            UnderwritingRuleHistory v1 = new UnderwritingRuleHistory();
            v1.setVersion(1);
            v1.setRuleCode("RULE_001");
            when(ruleRepository.findHistoryByRuleCode("RULE_001")).thenReturn(List.of(v2, v1));

            List<UnderwritingRuleHistory> result = service.getHistory("RULE_001");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getVersion()).isEqualTo(2);
            assertThat(result.get(1).getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("无历史记录 → 返回空列表")
        void noHistory() {
            when(ruleRepository.findHistoryByRuleCode("RULE_999")).thenReturn(List.of());

            List<UnderwritingRuleHistory> result = service.getHistory("RULE_999");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("wordingConfig - 话素模板解析")
    class Wording {

        private UnderwritingRule ruleWithWording(String wordingConfig) {
            UnderwritingRule rule = createRule("RW", "话素规则", RuleType.INSURED,
                    "{\"logic\":\"AND\",\"items\":[{\"feature\":\"age\",\"operator\":\"GTE\",\"value\":30}]}");
            rule.setWordingConfig(wordingConfig);
            return rule;
        }

        @Test
        @DisplayName("passed 规则 → 用 pass 模板")
        void passedRuleUsesPassTemplate() {
            featResult.putInsuredFeature("POL001", "INS001", Map.of("age", 35));
            String wordingJson = "{\"A\":{\"pass\":\"年龄{{age}}岁，通过\",\"fail\":\"年龄{{age}}岁，不通过\"},"
                    + "\"B\":{\"pass\":\"被保险人{{age}}岁符合\",\"fail\":\"年龄不足\"}}";
            UnderwritingRule rule = ruleWithWording(wordingJson);
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            var r = results.stream().filter(x -> x.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r.isPassed()).isTrue();
            assertThat(r.getWordingBySide()).containsEntry("A", "年龄35岁，通过");
            assertThat(r.getWordingBySide()).containsEntry("B", "被保险人35岁符合");
            assertThat(r.getWordingBySide()).doesNotContainKey("C");
        }

        @Test
        @DisplayName("failed 规则 → 用 fail 模板")
        void failedRuleUsesFailTemplate() {
            featResult.putInsuredFeature("POL001", "INS002", Map.of("age", 25));
            String wordingJson = "{\"A\":{\"pass\":\"通过\",\"fail\":\"年龄{{age}}岁不通过\"},"
                    + "\"C\":{\"pass\":\"通过\",\"fail\":\"被保险人{{age}}岁不通过\"}}";
            UnderwritingRule rule = ruleWithWording(wordingJson);
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            var r = results.stream().filter(x -> x.getTargetId().equals("INS002")).findFirst().orElseThrow();
            assertThat(r.isPassed()).isFalse();
            assertThat(r.getWordingBySide()).containsEntry("A", "年龄25岁不通过");
            assertThat(r.getWordingBySide()).containsEntry("C", "被保险人25岁不通过");
            assertThat(r.getWordingBySide()).doesNotContainKey("B");
        }

        @Test
        @DisplayName("wordingConfig 为 null")
        void nullWordingConfig() {
            featResult.putInsuredFeature("POL001", "INS001", Map.of("age", 35));
            UnderwritingRule rule = ruleWithWording(null);
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            var r = results.stream().filter(x -> x.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r.isPassed()).isTrue();
            assertThat(r.getWordingBySide()).isEmpty();
        }

        @Test
        @DisplayName("嵌套路径 {{score.level}}")
        void nestedMacroPath() {
            featResult.putInsuredFeature("POL001", "INS001",
                    Map.of("age", 35, "score", Map.of("level", "A")));
            String wordingJson = "{\"A\":{\"pass\":\"评分等级{{score.level}}，通过\"}}";
            UnderwritingRule rule = ruleWithWording(wordingJson);
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            var r = results.stream().filter(x -> x.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r.isPassed()).isTrue();
            assertThat(r.getWordingBySide()).containsEntry("A", "评分等级A，通过");
        }

        @Test
        @DisplayName("宏对应特征不存在 → 替换为空字符串")
        void missingMacroFeature() {
            featResult.putInsuredFeature("POL001", "INS001", Map.of("age", 35));
            String wordingJson = "{\"A\":{\"pass\":\"年龄{{age}}岁，{{missing}}信息\"}}";
            UnderwritingRule rule = ruleWithWording(wordingJson);
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var results = service.evaluate(order, featResult);

            var r = results.stream().filter(x -> x.getTargetId().equals("INS001")).findFirst().orElseThrow();
            assertThat(r.isPassed()).isTrue();
            assertThat(r.getWordingBySide()).containsEntry("A", "年龄35岁，信息");
        }
    }
}
