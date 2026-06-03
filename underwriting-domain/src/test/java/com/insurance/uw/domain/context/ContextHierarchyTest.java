package com.insurance.uw.domain.context;

import com.insurance.uw.domain.model.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Context Hierarchy - 上下文树构建与导航")
class ContextHierarchyTest {

    private OrderFeatureContext orderCtx;
    private PolicyFeatureContext polCtx1;
    private PolicyFeatureContext polCtx2;
    private InsuredFeatureContext insCtx1a;
    private InsuredFeatureContext insCtx1b;
    private InsuredFeatureContext insCtx2;

    @BeforeEach
    void setUp() {
        Product product1 = new Product("PROD001", "产品A");
        Product product2 = new Product("PROD002", "产品B");

        Applicant applicant1 = new Applicant("APP001", "张三", 35, "M");
        Applicant applicant2 = new Applicant("APP002", "李四", 30, "F");

        Insured insured1a = new Insured("INS001", "张三", 35, "M");
        Insured insured1b = new Insured("INS002", "王五", 28, "M");
        Insured insured2 = new Insured("INS003", "李四", 30, "F");

        Policy policy1 = new Policy("POL001", product1, applicant1, List.of(insured1a, insured1b));
        Policy policy2 = new Policy("POL002", product2, applicant2, List.of(insured2));

        Order order = new Order("ORD001", "ONLINE", null, List.of(policy1, policy2));

        orderCtx = new OrderFeatureContext(order);
        polCtx1 = orderCtx.getPolicies().get(0);
        polCtx2 = orderCtx.getPolicies().get(1);
        insCtx1a = polCtx1.getInsureds().get(0);
        insCtx1b = polCtx1.getInsureds().get(1);
        insCtx2 = polCtx2.getInsureds().get(0);
    }

    @Nested
    @DisplayName("OrderFeatureContext")
    class OrderContextTests {

        @Test
        @DisplayName("构建上下文树 → 递归创建所有子级")
        void buildsFullTree() {
            assertThat(orderCtx.getPolicies()).hasSize(2);
            assertThat(polCtx1.getInsureds()).hasSize(2);
            assertThat(polCtx2.getInsureds()).hasSize(1);
        }

        @Test
        @DisplayName("代理属性 → orderId, channel 可访问")
        void proxyProperties() {
            assertThat(orderCtx.getOrderId()).isEqualTo("ORD001");
            assertThat(orderCtx.getChannel()).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("findInsuredCtx → 跨保单查找被保人")
        void findInsuredCtx() {
            List<InsuredFeatureContext> found = orderCtx.findInsuredCtx("INS003");
            assertThat(found).hasSize(1);
            assertThat(found.get(0).getInsuredId()).isEqualTo("INS003");
        }

        @Test
        @DisplayName("findInsuredCtx → 不存在返回空列表")
        void findInsuredCtxNotFound() {
            assertThat(orderCtx.findInsuredCtx("NONEXIST")).isEmpty();
        }

        @Test
        @DisplayName("findPolicyCtx → 根据保单 ID 查找")
        void findPolicyCtx() {
            PolicyFeatureContext found = orderCtx.findPolicyCtx("POL002");
            assertThat(found).isNotNull();
            assertThat(found.getProductCode()).isEqualTo("PROD002");
        }

        @Test
        @DisplayName("findPolicyCtx → 不存在返回 null")
        void findPolicyCtxNotFound() {
            assertThat(orderCtx.findPolicyCtx("NONEXIST")).isNull();
        }

        @Test
        @DisplayName("getAllInsuredContexts → 扁平化所有被保人")
        void getAllInsuredContexts() {
            List<InsuredFeatureContext> all = orderCtx.getAllInsuredContexts();
            assertThat(all).hasSize(3);
            assertThat(all).extracting(InsuredFeatureContext::getInsuredId)
                    .containsExactlyInAnyOrder("INS001", "INS002", "INS003");
        }

        @Test
        @DisplayName("getInsuredsForFeature → 有 mapping 时按映射过滤")
        void getInsuredsForFeatureWithMapping() {
            orderCtx.setPolicyInsuredFeatureMap(
                    Map.of("POL001", Map.of("INS001", Set.of("f1"), "INS002", Set.of("f1"))));
            List<InsuredFeatureContext> result = orderCtx.getInsuredsForFeature("f1");

            assertThat(result).hasSize(2);
            assertThat(result).extracting(InsuredFeatureContext::getInsuredId)
                    .containsExactlyInAnyOrder("INS001", "INS002");
        }

        @Test
        @DisplayName("getInsuredsForFeature → mapping 未注入时回退到全部")
        void getInsuredsForFeatureFallbackOnNullMapping() {
            List<InsuredFeatureContext> result = orderCtx.getInsuredsForFeature("f1");

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("getInsuredsForFeature → mapping 中无该特征时回退到全部")
        void getInsuredsForFeatureFallbackOnMissingFeature() {
            orderCtx.setPolicyInsuredFeatureMap(
                    Map.of("POL001", Map.of("INS001", Set.of("f1"))));
            List<InsuredFeatureContext> result = orderCtx.getInsuredsForFeature("f2");

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("getInsuredsForFeature → mapping 值为空集合时回退到全部")
        void getInsuredsForFeatureFallbackOnEmptySet() {
            orderCtx.setPolicyInsuredFeatureMap(
                    Map.of("POL001", Map.of("INS001", Set.of())));
            List<InsuredFeatureContext> result = orderCtx.getInsuredsForFeature("f1");

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("getPoliciesForFeature → 有 mapping 时按映射过滤")
        void getPoliciesForFeatureWithMapping() {
            orderCtx.setPolicyInsuredFeatureMap(
                    Map.of("POL001", Map.of("INS001", Set.of("f1"))));
            List<PolicyFeatureContext> result = orderCtx.getPoliciesForFeature("f1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyId()).isEqualTo("POL001");
        }

        @Test
        @DisplayName("getPoliciesForFeature → mapping 未注入时回退到全部")
        void getPoliciesForFeatureFallback() {
            List<PolicyFeatureContext> result = orderCtx.getPoliciesForFeature("f1");
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("PolicyFeatureContext")
    class PolicyContextTests {

        @Test
        @DisplayName("代理属性 → policyId, productCode")
        void proxyProperties() {
            assertThat(polCtx1.getPolicyId()).isEqualTo("POL001");
            assertThat(polCtx1.getProductCode()).isEqualTo("PROD001");
        }

        @Test
        @DisplayName("productCode → product 为 null 时返回 null")
        void productCodeNullProduct() {
            Policy policy = new Policy("POL003", null, new Applicant("A", "X", 20, "M"), List.of());
            PolicyFeatureContext ctx = new PolicyFeatureContext(policy, orderCtx);

            assertThat(ctx.getProductCode()).isNull();
        }

        @Test
        @DisplayName("向上导航 → getOrderContext()")
        void upwardNavigation() {
            assertThat(polCtx1.getOrderContext()).isSameAs(orderCtx);
        }
    }

    @Nested
    @DisplayName("InsuredFeatureContext")
    class InsuredContextTests {

        @Test
        @DisplayName("代理属性 → 所有被保人字段可访问")
        void proxyProperties() {
            assertThat(insCtx1a.getInsuredId()).isEqualTo("INS001");
            assertThat(insCtx1a.getName()).isEqualTo("张三");
            assertThat(insCtx1a.getAge()).isEqualTo(35);
            assertThat(insCtx1a.getGender()).isEqualTo("M");
        }

        @Test
        @DisplayName("双向导航 → getPolicyContext()")
        void bidirectionalNavigation() {
            assertThat(insCtx1a.getPolicyContext()).isSameAs(polCtx1);
        }

        @Test
        @DisplayName("向上导航 → getOrderContext()")
        void upwardToOrder() {
            assertThat(insCtx1a.getOrderContext()).isSameAs(orderCtx);
        }

        @Test
        @DisplayName("getOrderFeature → 访问订单级特征值")
        void getOrderFeature() {
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            assertThat(insCtx1a.getOrderFeature("channel")).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("getOrderFeature → 无 orderContext 时返回 null")
        void getOrderFeatureNoContext() {
            Insured ins = new Insured("X", "X", 0, "X");
            InsuredFeatureContext solo = new InsuredFeatureContext(ins, null);
            assertThat(solo.getOrderFeature("channel")).isNull();
        }

        @Test
        @DisplayName("特征结果 key 不存在 → 返回 null")
        void getOrderFeatureMissing() {
            assertThat(insCtx1a.getOrderFeature("nonexistent")).isNull();
        }
    }

    @Nested
    @DisplayName("ApplicantFeatureContext")
    class ApplicantContextTests {

        @Test
        @DisplayName("代理属性 → 投保人字段可访问")
        void proxyProperties() {
            ApplicantFeatureContext appCtx = polCtx1.getApplicantCtx();
            assertThat(appCtx.getApplicantId()).isEqualTo("APP001");
            assertThat(appCtx.getName()).isEqualTo("张三");
            assertThat(appCtx.getAge()).isEqualTo(35);
        }

        @Test
        @DisplayName("getCustomerNos → 同人客户号列表")
        void customerNos() {
            Applicant applicant = new Applicant("A1", "X", 20, "M");
            applicant.setCustomerNos(List.of("CN001", "CN002"));
            ApplicantFeatureContext ctx = new ApplicantFeatureContext(applicant, null);

            assertThat(ctx.getCustomerNos()).containsExactly("CN001", "CN002");
        }
    }
}
