package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.context.*;
import com.insurance.uw.domain.model.entity.*;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ParamMappingCalcHandler - 参数映射处理器")
class ParamMappingCalcHandlerTest {

    private ParamMappingCalcHandler handler;
    private OrderFeatureContext orderCtx;
    private PolicyFeatureContext polCtx;

    @BeforeEach
    void setUp() {
        handler = new ParamMappingCalcHandler();

        Product product = new Product("PROD001", "测试产品");
        Applicant applicant = new Applicant("APP001", "投保人张三", 35, "M");
        Insured insured1 = new Insured("INS001", "张三", 35, "M");
        Insured insured2 = new Insured("INS002", "李四", 28, "F");

        Policy policy = new Policy("POL001", product, applicant, List.of(insured1, insured2));
        Order order = new Order("ORD001", "ONLINE", null, List.of(policy));

        orderCtx = new OrderFeatureContext(order);
        polCtx = orderCtx.getPolicies().get(0);
    }

    @Nested
    @DisplayName("getSupportedType")
    class SupportedType {

        @Test
        @DisplayName("返回 PARAM_MAPPING")
        void returnsParamMapping() {
            assertThat(handler.getSupportedType()).isEqualTo(CalcType.PARAM_MAPPING);
        }
    }

    @Nested
    @DisplayName("source 解析异常")
    class SourceParsingErrors {

        private FeatureConfig fc(String source) {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig calcConfig = new CalcConfig();
            calcConfig.setSource(source);
            config.setCalcConfig(calcConfig);
            return config;
        }

        @Test
        @DisplayName("source 为 null → 抛出异常")
        void nullSource() {
            FeatureConfig config = fc(null);
            assertThatThrownBy(() -> handler.execute(orderCtx, config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("source 未配置");
        }

        @Test
        @DisplayName("source 为空白 → 抛出异常")
        void blankSource() {
            FeatureConfig config = fc("  ");
            assertThatThrownBy(() -> handler.execute(orderCtx, config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("source 未配置");
        }

        @Test
        @DisplayName("source 缺少点号 → 抛出异常")
        void missingDot() {
            FeatureConfig config = fc("insuredAge");
            assertThatThrownBy(() -> handler.execute(orderCtx, config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("格式无效");
        }

        @Test
        @DisplayName("source 多个点号 → 按第一个点分割，后面作为嵌套路径")
        void multipleDots() {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("policy.product.productCode");
            config.setCalcConfig(cc);

            // "policy.product.productCode" → entityType="policy", fieldName="product.productCode"
            Map<String, Object> result = handler.execute(orderCtx, config);

            assertThat(result).containsKey("POL001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("POL001");
            assertThat(featureMap).containsEntry("TEST_FC", "PROD001");
        }

        @Test
        @DisplayName("未知的上下文类型 → 抛出异常")
        void unknownContextType() {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("insured.age");
            config.setCalcConfig(cc);

            assertThatThrownBy(() -> handler.execute("not a context", config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不支持的上下文类型");
        }

        @Test
        @DisplayName("无效的 entityType → 抛出异常")
        void invalidEntityType() {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("unknown.field");
            config.setCalcConfig(cc);

            assertThatThrownBy(() -> handler.execute(orderCtx, config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entityType 无效");
        }

        @Test
        @DisplayName("不存在的字段名 → 抛出异常")
        void nonexistentField() {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("insured.nonExistentField");
            config.setCalcConfig(cc);

            assertThatThrownBy(() -> handler.execute(orderCtx, config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("实体无字段");
        }
    }

    @Nested
    @DisplayName("ORDER 级聚合 - executeOrderLevel")
    class OrderLevelExecute {

        private FeatureConfig fc(String source) {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource(source);
            config.setCalcConfig(cc);
            return config;
        }

        @Test
        @DisplayName("entityType=order → 读取 Order 字段，key 为 __ORDER__")
        void orderEntityType() {
            Map<String, Object> result = handler.execute(orderCtx, fc("order.channel"));

            assertThat(result).containsKey("__ORDER__");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("__ORDER__");
            assertThat(featureMap).containsEntry("TEST_FC", "ONLINE");
        }

        @Test
        @DisplayName("entityType=policy → 遍历相关保单，读取 Policy 字段")
        void policyEntityType() {
            Map<String, Object> result = handler.execute(orderCtx, fc("policy.id"));

            assertThat(result).containsKey("POL001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("POL001");
            assertThat(featureMap).containsEntry("TEST_FC", "POL001");
        }

        @Test
        @DisplayName("entityType=policy → 嵌套字段 product.productCode")
        void policyNestedField() {
            Map<String, Object> result = handler.execute(orderCtx, fc("policy.product.productCode"));

            assertThat(result).containsKey("POL001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("POL001");
            assertThat(featureMap).containsEntry("TEST_FC", "PROD001");
        }

        @Test
        @DisplayName("entityType=insured → 遍历相关被保人，读取 Insured 字段")
        void insuredEntityType() {
            // inject mapping so getInsuredsForFeature works
            orderCtx.setFeatureInsuredMapping(Map.of("TEST_FC", java.util.Set.of("INS001")));
            Map<String, Object> result = handler.execute(orderCtx, fc("insured.age"));

            assertThat(result).containsKey("INS001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("INS001");
            assertThat(featureMap).containsEntry("TEST_FC", 35);
        }

        @Test
        @DisplayName("entityType=applicant → 遍历相关保单的投保人")
        void applicantEntityType() {
            Map<String, Object> result = handler.execute(orderCtx, fc("applicant.age"));

            assertThat(result).containsKey("APP001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("APP001");
            assertThat(featureMap).containsEntry("TEST_FC", 35);
        }

        @Test
        @DisplayName("ORDER 级 order 实体 → 完整字段读取（id）")
        void orderEntityId() {
            Map<String, Object> result = handler.execute(orderCtx, fc("order.id"));

            assertThat(result).containsKey("__ORDER__");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("__ORDER__");
            assertThat(featureMap).containsEntry("TEST_FC", "ORD001");
        }
    }

    @Nested
    @DisplayName("POLICY 级聚合 - executePolicyLevel")
    class PolicyLevelExecute {

        private FeatureConfig fc(String source) {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("TEST_FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource(source);
            config.setCalcConfig(cc);
            return config;
        }

        @Test
        @DisplayName("entityType=order → 读取订单字段")
        void orderEntityType() {
            Map<String, Object> result = handler.execute(polCtx, fc("order.id"));

            assertThat(result).containsKey("__ORDER__");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("__ORDER__");
            assertThat(featureMap).containsEntry("TEST_FC", "ORD001");
        }

        @Test
        @DisplayName("entityType=policy → 读取保单字段")
        void policyEntityType() {
            Map<String, Object> result = handler.execute(polCtx, fc("policy.id"));

            assertThat(result).containsKey("POL001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("POL001");
            assertThat(featureMap).containsEntry("TEST_FC", "POL001");
        }

        @Test
        @DisplayName("entityType=policy → 嵌套读取 product.productCode")
        void policyNestedProductCode() {
            Map<String, Object> result = handler.execute(polCtx, fc("policy.product.productCode"));

            assertThat(result).containsKey("POL001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("POL001");
            assertThat(featureMap).containsEntry("TEST_FC", "PROD001");
        }

        @Test
        @DisplayName("entityType=insured → 遍历所有被保人")
        void insuredEntityType() {
            Map<String, Object> result = handler.execute(polCtx, fc("insured.name"));

            assertThat(result).containsKeys("INS001", "INS002");
            @SuppressWarnings("unchecked")
            Map<String, Object> f1 = (Map<String, Object>) result.get("INS001");
            assertThat(f1).containsEntry("TEST_FC", "张三");

            @SuppressWarnings("unchecked")
            Map<String, Object> f2 = (Map<String, Object>) result.get("INS002");
            assertThat(f2).containsEntry("TEST_FC", "李四");
        }

        @Test
        @DisplayName("entityType=applicant → 读取投保人字段")
        void applicantEntityType() {
            Map<String, Object> result = handler.execute(polCtx, fc("applicant.name"));

            assertThat(result).containsKey("APP001");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("APP001");
            assertThat(featureMap).containsEntry("TEST_FC", "投保人张三");
        }

        @Test
        @DisplayName("entityType=order 但在 POLICY 级没有 orderContext → 跳过")
        void orderEntityTypeNoOrderContext() {
            // Create a PolicyFeatureContext without a parent OrderFeatureContext
            Policy orphanPolicy = new Policy("POL_ORPHAN", new Product("P", "p"), null, List.of());
            PolicyFeatureContext orphanCtx = new PolicyFeatureContext(orphanPolicy, null);

            Map<String, Object> result = handler.execute(orphanCtx, fc("order.id"));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("applicant 为 null → 跳过")
        void applicantNull() {
            Policy policy = new Policy("POL_NOAPP", new Product("P", "p"), null, List.of());
            PolicyFeatureContext ctx = new PolicyFeatureContext(policy, orderCtx);

            Map<String, Object> result = handler.execute(ctx, fc("applicant.name"));
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("反射缓存")
    class ReflectionCache {

        @Test
        @DisplayName("同一实体多次读取 → 复用缓存的 Method")
        void cachesGetterMethods() {
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("insured.age");
            config.setCalcConfig(cc);

            // First call populates cache
            handler.execute(orderCtx, config);
            // Second call should use cache
            Map<String, Object> result = handler.execute(orderCtx, config);

            assertThat(result).containsKeys("INS001", "INS002");
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("读取 null 实体 → 返回 null 值")
        void nullEntity() {
            // Order with no policies → null applicant through policy chain
            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("policy.id");
            config.setCalcConfig(cc);

            Order emptyOrder = new Order("ORD", "CH", null, List.of());
            OrderFeatureContext emptyCtx = new OrderFeatureContext(emptyOrder);

            Map<String, Object> result = handler.execute(emptyCtx, config);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("双保单 → 两个保单都被处理")
        void multiplePolicies() {
            Product prod = new Product("PROD", "产品");
            Applicant app1 = new Applicant("APP1", "A", 30, "M");
            Applicant app2 = new Applicant("APP2", "B", 40, "F");
            Policy pol1 = new Policy("POL1", prod, app1, List.of());
            Policy pol2 = new Policy("POL2", prod, app2, List.of());
            Order order = new Order("ORD", "CH", null, List.of(pol1, pol2));
            OrderFeatureContext ctx = new OrderFeatureContext(order);

            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("policy.id");
            config.setCalcConfig(cc);

            Map<String, Object> result = handler.execute(ctx, config);

            assertThat(result).containsKeys("POL1", "POL2");
        }

        @Test
        @DisplayName("Applicant 的 getCustomerNos → 读取列表字段")
        void applicantCustomerNos() {
            Applicant applicant = new Applicant("APP", "X", 20, "M");
            applicant.setCustomerNos(List.of("CN001", "CN002"));
            Policy policy = new Policy("POL", new Product("P", "p"), applicant, List.of());
            Order order = new Order("ORD", "CH", null, List.of(policy));
            OrderFeatureContext ctx = new OrderFeatureContext(order);

            FeatureConfig config = new FeatureConfig();
            config.setFeatureCode("FC");
            CalcConfig cc = new CalcConfig();
            cc.setSource("applicant.customerNos");
            config.setCalcConfig(cc);

            Map<String, Object> result = handler.execute(ctx, config);

            assertThat(result).containsKey("APP");
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) result.get("APP");
            @SuppressWarnings("unchecked")
            List<String> value = (List<String>) featureMap.get("FC");
            assertThat(value).containsExactly("CN001", "CN002");
        }
    }
}
