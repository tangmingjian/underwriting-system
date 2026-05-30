package com.insurance.uw.domain.service;

import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeatureCollector - 特征收集器")
class FeatureCollectorTest {

    private OrderFeatureContext orderCtx;
    private PolicyFeatureContext polCtx;
    private InsuredFeatureContext insCtx;
    private ApplicantFeatureContext appCtx;

    @BeforeEach
    void setUp() {
        Product product = new Product("PROD001", "测试产品");
        Applicant applicant = new Applicant("APP001", "张三", 35, "M");
        Insured insured = new Insured("INS001", "张三", 35, "M");

        Policy policy = new Policy("POL001", product, applicant, List.of(insured));
        Order order = new Order("ORD001", "ONLINE", null, List.of(policy));

        orderCtx = new OrderFeatureContext(order);
        polCtx = orderCtx.getPolicies().get(0);
        insCtx = polCtx.getInsureds().get(0);
        appCtx = polCtx.getApplicantCtx();
    }

    @Nested
    @DisplayName("collectForInsured")
    class CollectForInsured {

        @Test
        @DisplayName("所有层级都有特征 → 按优先级合并")
        void allLevelsPopulated() {
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            polCtx.getPolicyFeatures().put("productCode", "PROD001");
            appCtx.getFeatures().put("applicantAge", 35);
            insCtx.getAcquiredFeatures().put("insuredAge", 35);

            Map<String, Object> result = FeatureCollector.collectForInsured(insCtx);

            assertThat(result)
                    .containsEntry("channel", "ONLINE")
                    .containsEntry("productCode", "PROD001")
                    .containsEntry("applicantAge", 35)
                    .containsEntry("insuredAge", 35);
        }

        @Test
        @DisplayName("被保人特征覆盖同名的投保人特征")
        void insuredOverridesApplicant() {
            appCtx.getFeatures().put("age", 35);
            insCtx.getAcquiredFeatures().put("age", 40);

            Map<String, Object> result = FeatureCollector.collectForInsured(insCtx);

            assertThat(result).containsEntry("age", 40);
        }

        @Test
        @DisplayName("被保人特征覆盖同名订单特征")
        void insuredOverridesOrder() {
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            insCtx.getAcquiredFeatures().put("channel", "OFFLINE");

            Map<String, Object> result = FeatureCollector.collectForInsured(insCtx);

            assertThat(result).containsEntry("channel", "OFFLINE");
        }

        @Test
        @DisplayName("被保人无 policy context → 仅返回自身特征")
        void nullPolicyContext() {
            Insured ins = new Insured("INS002", "李四", 30, "F");
            InsuredFeatureContext solo = new InsuredFeatureContext(ins, null);
            solo.getAcquiredFeatures().put("age", 30);

            Map<String, Object> result = FeatureCollector.collectForInsured(solo);

            assertThat(result).containsEntry("age", 30);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("上下文全空 → 返回空 Map")
        void emptyContexts() {
            orderCtx.getOrderFeatures().clear();
            polCtx.getPolicyFeatures().clear();
            appCtx.getFeatures().clear();
            insCtx.getAcquiredFeatures().clear();

            Map<String, Object> result = FeatureCollector.collectForInsured(insCtx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("收集结果保持插入顺序")
        void preservesInsertionOrder() {
            orderCtx.getOrderFeatures().put("A", 1);
            polCtx.getPolicyFeatures().put("B", 2);
            appCtx.getFeatures().put("C", 3);
            insCtx.getAcquiredFeatures().put("D", 4);

            Map<String, Object> result = FeatureCollector.collectForInsured(insCtx);

            List<String> keys = List.copyOf(result.keySet());
            assertThat(keys).containsSequence("A", "B", "C", "D");
        }
    }

    @Nested
    @DisplayName("collectForApplicant")
    class CollectForApplicant {

        @Test
        @DisplayName("全部特征 → 按优先级合并")
        void allLevelsPopulated() {
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            polCtx.getPolicyFeatures().put("productCode", "PROD001");
            appCtx.getFeatures().put("applicantAge", 35);

            Map<String, Object> result = FeatureCollector.collectForApplicant(polCtx);

            assertThat(result)
                    .containsEntry("channel", "ONLINE")
                    .containsEntry("productCode", "PROD001")
                    .containsEntry("applicantAge", 35);
        }

        @Test
        @DisplayName("投保人特征覆盖同名的保单特征")
        void applicantOverridesPolicy() {
            polCtx.getPolicyFeatures().put("risk", "LOW");
            appCtx.getFeatures().put("risk", "HIGH");

            Map<String, Object> result = FeatureCollector.collectForApplicant(polCtx);

            assertThat(result).containsEntry("risk", "HIGH");
        }
    }

    @Nested
    @DisplayName("collectForPolicy")
    class CollectForPolicy {

        @Test
        @DisplayName("全部特征 → 按优先级合并")
        void allLevelsPopulated() {
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            polCtx.getPolicyFeatures().put("productCode", "PROD001");

            Map<String, Object> result = FeatureCollector.collectForPolicy(polCtx);

            assertThat(result)
                    .containsEntry("channel", "ONLINE")
                    .containsEntry("productCode", "PROD001");
        }

        @Test
        @DisplayName("保单特征覆盖同名的订单特征")
        void policyOverridesOrder() {
            orderCtx.getOrderFeatures().put("channel", "ONLINE");
            polCtx.getPolicyFeatures().put("channel", "OFFLINE");

            Map<String, Object> result = FeatureCollector.collectForPolicy(polCtx);

            assertThat(result).containsEntry("channel", "OFFLINE");
        }
    }
}
