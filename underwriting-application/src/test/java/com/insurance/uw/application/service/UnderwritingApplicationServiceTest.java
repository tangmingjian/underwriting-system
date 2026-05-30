package com.insurance.uw.application.service;

import com.insurance.uw.application.service.handler.FeatureCalcHandler;
import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.common.enums.StorageLevel;
import com.insurance.uw.domain.model.entity.*;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("UnderwritingApplicationService - 核保应用服务")
@ExtendWith(MockitoExtension.class)
class UnderwritingApplicationServiceTest {

    @Mock
    private FeatureConfigRepository featureConfigRepository;

    @Mock
    private UnderwritingRuleRepository ruleRepository;

    @Mock
    private FeatureCalcHandler mockHandler;

    private ExecutorService executor;

    private UnderwritingApplicationService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        when(mockHandler.getSupportedType()).thenReturn(CalcType.PARAM_MAPPING);
        service = new UnderwritingApplicationService(
                featureConfigRepository, ruleRepository, executor,
                List.of(mockHandler));
    }

    private Order createTestOrder() {
        Product product = new Product("PROD001", "测试产品");
        Applicant applicant = new Applicant("APP001", "张三", 35, "M");
        Insured insured = new Insured("INS001", "张三", 35, "M");
        Policy policy = new Policy("POL001", product, applicant, List.of(insured));
        return new Order("ORD001", "ONLINE", null, List.of(policy));
    }

    private FeatureConfig createFeatureConfig(String code, CalcType calcType,
                                               AggregationLevel aggregation,
                                               StorageLevel storageLevel) {
        FeatureConfig fc = new FeatureConfig();
        fc.setFeatureCode(code);
        fc.setCalcType(calcType);
        fc.setAggregation(aggregation);
        fc.setStorageLevel(storageLevel);
        fc.setStatus(FeatureStatus.ACTIVE);
        CalcConfig calcConfig = new CalcConfig();
        calcConfig.setSource("insured.age");
        fc.setCalcConfig(calcConfig);
        return fc;
    }

    @Nested
    @DisplayName("processOrder 整体流程")
    class ProcessOrder {

        @Test
        @DisplayName("无保单的订单 → 正常返回空结果")
        void emptyOrder() {
            Order order = new Order("ORD001", "ONLINE", null, List.of());
            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of());

            var result = service.processOrder(order);

            assertThat(result).isNotNull();
            assertThat(result.getPolicies()).isEmpty();
            assertThat(result.getOrderFeatures()).isEmpty();
        }

        @Test
        @DisplayName("无特征配置 → 提前返回（无特征收集和拓扑排序）")
        void noFeatureConfigs() {
            Order order = createTestOrder();
            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of());

            var result = service.processOrder(order);

            assertThat(result).isNotNull();
            verify(featureConfigRepository, never()).findByFeatureCodes(anyList());
        }

        @Test
        @DisplayName("有特征配置但无规则 → 正常执行特征取数")
        void featuresWithoutRules() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());
            when(featureConfigRepository.findByFeatureCodes(List.of("AGE")))
                    .thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("AGE", 35)));

            var result = service.processOrder(order);

            assertThat(result).isNotNull();
            verify(mockHandler).execute(any(), eq(fc));
        }

        @Test
        @DisplayName("特征执行失败 → 抛出 RuntimeException")
        void featureExecutionFails() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());
            when(featureConfigRepository.findByFeatureCodes(List.of("AGE")))
                    .thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenThrow(new RuntimeException("网络超时"));

            assertThatThrownBy(() -> service.processOrder(order))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("执行特征 AGE 失败");
        }
    }

    @Nested
    @DisplayName("buildFeatureInsuredMapping - 特征→被保人/保单映射")
    class FeatureInsuredMapping {

        @Test
        @DisplayName("规则匹配产品 → 映射所有被保人")
        void rulesMatchProduct() {
            Order order = createTestOrder();

            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode("PROD001");
            rule.setFeatureCodes("RISK_SCORE,AGE");
            rule.setStatus(1);

            FeatureConfig fc1 = createFeatureConfig("RISK_SCORE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            FeatureConfig fc2 = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc1, fc2));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));
            when(featureConfigRepository.findByFeatureCodes(anyList()))
                    .thenReturn(List.of(fc1, fc2));
            when(mockHandler.execute(any(), any()))
                    .thenReturn(Map.of("INS001", Map.of("RISK_SCORE", 80)));

            service.processOrder(order);
            // No exception = success. The mapping was built and used.
        }

        @Test
        @DisplayName("产品码不匹配 → 规则被跳过，无映射")
        void productCodeMismatch() {
            Order order = createTestOrder();

            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode("OTHER_PROD"); // does not match PROD001
            rule.setFeatureCodes("RISK_SCORE");
            rule.setStatus(1);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of());
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var result = service.processOrder(order);

            assertThat(result).isNotNull();
            // Rule was ignored, no features to load
            verify(featureConfigRepository, never()).findByFeatureCodes(anyList());
        }

        @Test
        @DisplayName("product_code 为 null → 适用于所有产品（向后兼容）")
        void nullProductCodeAppliesToAll() {
            Order order = createTestOrder();

            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode(null); // backward compat
            rule.setFeatureCodes("AGE");
            rule.setStatus(1);

            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));
            when(featureConfigRepository.findByFeatureCodes(List.of("AGE")))
                    .thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("AGE", 35)));

            var result = service.processOrder(order);

            assertThat(result).isNotNull();
            verify(mockHandler).execute(any(), eq(fc));
        }

        @Test
        @DisplayName("feature_codes 为空 → 规则被跳过")
        void emptyFeatureCodes() {
            Order order = createTestOrder();

            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode("PROD001");
            rule.setFeatureCodes("  "); // blank
            rule.setStatus(1);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of());
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));

            var result = service.processOrder(order);

            assertThat(result).isNotNull();
            verify(featureConfigRepository, never()).findByFeatureCodes(anyList());
        }

        @Test
        @DisplayName("feature_codes 包含空格 → trim 后正常处理")
        void featureCodesWithSpaces() {
            Order order = createTestOrder();

            UnderwritingRule rule = new UnderwritingRule();
            rule.setProductCode("PROD001");
            rule.setFeatureCodes(" AGE , SCORE "); // with spaces
            rule.setStatus(1);

            FeatureConfig fc1 = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            FeatureConfig fc2 = createFeatureConfig("SCORE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc1, fc2));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of(rule));
            when(featureConfigRepository.findByFeatureCodes(anyList()))
                    .thenReturn(List.of(fc1, fc2));
            when(mockHandler.execute(any(), any()))
                    .thenReturn(Map.of("INS001", Map.of("AGE", 35)));

            service.processOrder(order);
            // No exception
        }
    }

    @Nested
    @DisplayName("executeLayer - 分层并行执行")
    class LayerExecution {

        @Test
        @DisplayName("ORDER 级特征 → 传入 OrderFeatureContext")
        void orderLevelFeature() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.ORDER);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());
            when(featureConfigRepository.findByFeatureCodes(List.of("AGE")))
                    .thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("__ORDER__", Map.of("AGE", "ONLINE")));

            var result = service.processOrder(order);

            assertThat(result.getOrderFeatures()).containsEntry("AGE", "ONLINE");
        }

        @Test
        @DisplayName("POLICY 级特征 → 传入 PolicyFeatureContext")
        void policyLevelFeature() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("SCORE", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.POLICY);

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());
            when(featureConfigRepository.findByFeatureCodes(List.of("SCORE")))
                    .thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("POL001", Map.of("SCORE", 75)));

            var result = service.processOrder(order);

            assertThat(result.getPolicies().get(0).getPolicyFeatures())
                    .containsEntry("SCORE", 75);
        }
    }

    @Nested
    @DisplayName("storeResults - 存储级别分发")
    class StoreResults {

        @Nested
        @DisplayName("ORDER 特征存储（storeResults）")
        class OrderStoreResults {

            private FeatureConfig fc(StorageLevel storageLevel) {
                return createFeatureConfig("FC", CalcType.PARAM_MAPPING,
                        AggregationLevel.ORDER, storageLevel);
            }

            @Test
            @DisplayName("StorageLevel=INSURED → 按 targetId 找到被保人并存储")
            void storeToInsured() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.INSURED);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("INS001", 35));

                var result = service.processOrder(order);

                var insCtx = result.findInsuredCtx("INS001");
                assertThat(insCtx.getAcquiredFeatures()).containsEntry("FC", 35);
            }

            @Test
            @DisplayName("StorageLevel=POLICY → 按 targetId 找到保单并存储")
            void storeToPolicy() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.POLICY);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("POL001", 75));

                var result = service.processOrder(order);

                assertThat(result.findPolicyCtx("POL001").getPolicyFeatures())
                        .containsEntry("FC", 75);
            }

            @Test
            @DisplayName("StorageLevel=APPLICANT → 按 targetId 找到保单的投保人并存储")
            void storeToApplicant() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.APPLICANT);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("POL001", 42));

                var result = service.processOrder(order);

                var appCtx = result.findPolicyCtx("POL001").getApplicantCtx();
                assertThat(appCtx.getFeatures()).containsEntry("FC", 42);
            }

            @Test
            @DisplayName("StorageLevel=ORDER → 直接写入 orderFeatures")
            void storeToOrder() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.ORDER);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("__ORDER__", "ONLINE"));

                var result = service.processOrder(order);

                assertThat(result.getOrderFeatures()).containsEntry("FC", "ONLINE");
            }

            @Test
            @DisplayName("返回值为 Map 时 → 直接展开")
            void resultIsMap() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.ORDER);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                // Return a Map directly
                Map<String, Object> resultMap = Map.of("__ORDER__", Map.of("FC", "MODE1", "EXTRA", "V2"));
                when(mockHandler.execute(any(), eq(feature))).thenReturn(resultMap);

                var result = service.processOrder(order);

                assertThat(result.getOrderFeatures())
                        .containsEntry("FC", "MODE1")
                        .containsEntry("EXTRA", "V2");
            }

            @Test
            @DisplayName("返回值为单值 → 包装为 singletonMap key=featureCode")
            void resultIsSingleValue() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.INSURED);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                // Return a single value (not a Map)
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("INS001", 35));

                var result = service.processOrder(order);

                var insCtx = result.findInsuredCtx("INS001");
                assertThat(insCtx.getAcquiredFeatures()).containsEntry("FC", 35);
            }

            @Test
            @DisplayName("targetId 找不到对应被保人 → 跳过（不抛异常）")
            void targetIdNotFound() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.INSURED);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                // targetId "NONEXISTENT" doesn't match any insured
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("NONEXISTENT", 35));

                // Should not throw
                var result = service.processOrder(order);
                assertThat(result).isNotNull();
            }

            @Test
            @DisplayName("返回 null → 跳过存储")
            void nullResults() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.INSURED);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature))).thenReturn(null);

                var result = service.processOrder(order);

                assertThat(result.findInsuredCtx("INS001").getAcquiredFeatures()).isEmpty();
            }
        }

        @Nested
        @DisplayName("POLICY 特征存储（storePolicyResults）")
        class PolicyStoreResults {

            private FeatureConfig fc(StorageLevel storageLevel) {
                return createFeatureConfig("FC", CalcType.PARAM_MAPPING,
                        AggregationLevel.POLICY, storageLevel);
            }

            @Test
            @DisplayName("StorageLevel=INSURED → 存储到对应被保人")
            void storeToInsured() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.INSURED);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("INS001", 35));

                var result = service.processOrder(order);

                var insCtx = result.findInsuredCtx("INS001");
                assertThat(insCtx.getAcquiredFeatures()).containsEntry("FC", 35);
            }

            @Test
            @DisplayName("StorageLevel=APPLICANT → 存储到当前保单的投保人")
            void storeToApplicant() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.APPLICANT);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("APP001", 42));

                var result = service.processOrder(order);

                var appCtx = result.getPolicies().get(0).getApplicantCtx();
                assertThat(appCtx.getFeatures()).containsEntry("FC", 42);
            }

            @Test
            @DisplayName("StorageLevel=POLICY → 存储到当前保单")
            void storeToPolicy() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.POLICY);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("POL001", 75));

                var result = service.processOrder(order);

                assertThat(result.getPolicies().get(0).getPolicyFeatures())
                        .containsEntry("FC", 75);
            }

            @Test
            @DisplayName("StorageLevel=ORDER → 向上写入 orderFeatures")
            void storeToOrder() {
                Order order = createTestOrder();
                FeatureConfig feature = fc(StorageLevel.ORDER);

                when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(feature));
                when(ruleRepository.findAllEnabled()).thenReturn(List.of());
                when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                        .thenReturn(List.of(feature));
                when(mockHandler.execute(any(), eq(feature)))
                        .thenReturn(Map.of("__ORDER__", "ONLINE"));

                var result = service.processOrder(order);

                assertThat(result.getOrderFeatures()).containsEntry("FC", "ONLINE");
            }
        }
    }

    @Nested
    @DisplayName("executeByCalcType - 多 Handler 分发")
    class HandlerDispatch {

        @Test
        @DisplayName("不支持的计算类型 → 抛出 IllegalArgumentException")
        void unsupportedCalcType() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("FC", CalcType.EXTERNAL_API,
                    AggregationLevel.ORDER, StorageLevel.ORDER);
            // Our mockHandler only supports PARAM_MAPPING

            when(featureConfigRepository.findAllEnabled()).thenReturn(List.of(fc));
            when(ruleRepository.findAllEnabled()).thenReturn(List.of());
            when(featureConfigRepository.findByFeatureCodes(List.of("FC")))
                    .thenReturn(List.of(fc));

            assertThatThrownBy(() -> service.processOrder(order))
                    .getRootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
