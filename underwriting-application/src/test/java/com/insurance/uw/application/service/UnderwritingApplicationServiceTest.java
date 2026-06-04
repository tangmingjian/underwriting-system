package com.insurance.uw.application.service;

import com.insurance.uw.common.constants.FeatureConstants;
import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.common.enums.StorageLevel;
import com.insurance.uw.domain.model.entity.*;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.service.FeatureDependencyResolver;
import com.insurance.uw.domain.service.FeatureResultCache;
import com.insurance.uw.application.feature.handler.FeatureCalcHandler;
import com.insurance.uw.application.feature.impl.FeatureExtractionServiceImpl;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FeatureExtractionServiceImpl - 特征取数服务")
@ExtendWith(MockitoExtension.class)
class UnderwritingApplicationServiceTest {

    @Mock
    private FeatureConfigRepository featureConfigRepository;

    @Mock
    private FeatureCalcHandler mockHandler;

    @Mock
    private FeatureResultCache featureResultCache;

    private ExecutorService executor;

    private FeatureExtractionServiceImpl service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        when(mockHandler.getSupportedType()).thenReturn(CalcType.PARAM_MAPPING);
        service = new FeatureExtractionServiceImpl(
                featureConfigRepository, new FeatureDependencyResolver(),
                executor,
                List.of(mockHandler),
                featureResultCache);
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

    private FeatureExtractionRequest buildRequest(Order order, Set<String> featureCodes) {
        FeatureExtractionRequest req = new FeatureExtractionRequest();
        req.setOrder(order);
        if (featureCodes != null && !featureCodes.isEmpty()) {
            Map<String, Map<String, Set<String>>> insuredMap = new HashMap<>();
            Map<String, Map<String, Set<String>>> applicantMap = new HashMap<>();
            for (Policy policy : order.getPolicies()) {
                Map<String, Set<String>> insuredInner = new HashMap<>();
                for (Insured insured : policy.getInsureds()) {
                    insuredInner.put(insured.getId(), featureCodes);
                }
                insuredMap.put(policy.getId(), insuredInner);
                if (policy.getApplicant() != null) {
                    applicantMap.put(policy.getId(),
                            Map.of(policy.getApplicant().getId(), featureCodes));
                }
            }
            req.setPolicyInsuredFeatureMap(insuredMap);
            req.setPolicyApplicantFeatureMap(applicantMap);
        }
        return req;
    }

    @Nested
    @DisplayName("extract 整体流程")
    class Extract {

        @Test
        @DisplayName("无保单的订单 → 正常返回空结果")
        void emptyOrder() {
            Order order = new Order("ORD001", "ONLINE", null, List.of());
            FeatureExtractionRequest req = buildRequest(order, Set.of());

            FeatureExtractionResult result = service.extract(req);

            assertThat(result).isNotNull();
            assertThat(result.getOrderFeatures()).isEmpty();
            assertThat(result.getPolicyFeatures()).isEmpty();
        }

        @Test
        @DisplayName("featureCodes 为空 → 跳过取数直接返回")
        void noFeatureCodes() {
            Order order = createTestOrder();
            FeatureExtractionRequest req = buildRequest(order, Set.of());

            FeatureExtractionResult result = service.extract(req);

            assertThat(result).isNotNull();
            verify(featureConfigRepository, never()).findByFeatureCodes(any());
        }

        @Test
        @DisplayName("有特征配置 → 正常执行特征取数")
        void featuresExtracted() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("AGE", 35)));

            FeatureExtractionRequest req = buildRequest(order, Set.of("AGE"));
            FeatureExtractionResult result = service.extract(req);

            assertThat(result).isNotNull();
            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001")).containsEntry("AGE", 35);
            verify(mockHandler).execute(any(), eq(fc));
        }

        @Test
        @DisplayName("特征执行失败 → 抛出 RuntimeException")
        void featureExecutionFails() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenThrow(new RuntimeException("网络超时"));

            FeatureExtractionRequest req = buildRequest(order, Set.of("AGE"));

            assertThatThrownBy(() -> service.extract(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("执行特征 AGE 失败");
        }
    }

    @Nested
    @DisplayName("expandDependencies - 传递依赖展开")
    class ExpandDependencies {

        @Test
        @DisplayName("请求 B，B dependsOn=[A] → 展开为 {A, B}")
        void transitiveExpansion() {
            FeatureConfig fcA = new FeatureConfig();
            fcA.setFeatureCode("A");
            fcA.setCalcType(CalcType.PARAM_MAPPING);
            fcA.setStatus(FeatureStatus.ACTIVE);

            FeatureConfig fcB = new FeatureConfig();
            fcB.setFeatureCode("B");
            fcB.setCalcType(CalcType.PARAM_MAPPING);
            fcB.setDependsOn(List.of("A"));
            fcB.setStatus(FeatureStatus.ACTIVE);

            Set<String> expanded = service.expandDependencies(
                    Set.of("B"), Map.of("A", fcA, "B", fcB));

            assertThat(expanded).containsExactly("B", "A");
        }

        @Test
        @DisplayName("无依赖 → 返回原集合")
        void noDependencies() {
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("X");
            fc.setCalcType(CalcType.PARAM_MAPPING);
            fc.setStatus(FeatureStatus.ACTIVE);

            Set<String> expanded = service.expandDependencies(
                    Set.of("X"), Map.of("X", fc));

            assertThat(expanded).containsExactly("X");
        }

        @Test
        @DisplayName("深度链 A→B→C → 请求 C 展开为 {C, B, A}")
        void deepChain() {
            FeatureConfig fcA = new FeatureConfig();
            fcA.setFeatureCode("A");
            fcA.setStatus(FeatureStatus.ACTIVE);

            FeatureConfig fcB = new FeatureConfig();
            fcB.setFeatureCode("B");
            fcB.setDependsOn(List.of("A"));
            fcB.setStatus(FeatureStatus.ACTIVE);

            FeatureConfig fcC = new FeatureConfig();
            fcC.setFeatureCode("C");
            fcC.setDependsOn(List.of("B"));
            fcC.setStatus(FeatureStatus.ACTIVE);

            Set<String> expanded = service.expandDependencies(
                    Set.of("C"), Map.of("A", fcA, "B", fcB, "C", fcC));

            assertThat(expanded).containsExactly("C", "B", "A");
        }

        @Test
        @DisplayName("已包含的依赖不重复展开（循环安全）")
        void noDuplicateExpansion() {
            FeatureConfig fcA = new FeatureConfig();
            fcA.setFeatureCode("A");
            fcA.setStatus(FeatureStatus.ACTIVE);

            FeatureConfig fcB = new FeatureConfig();
            fcB.setFeatureCode("B");
            fcB.setDependsOn(List.of("A"));
            fcB.setStatus(FeatureStatus.ACTIVE);

            Set<String> expanded = service.expandDependencies(
                    Set.of("A", "B"), Map.of("A", fcA, "B", fcB));

            assertThat(expanded).hasSize(2);
        }
    }

    @Nested
    @DisplayName("policyInsuredFeatureMap / policyApplicantFeatureMap 映射注入")
    class MappingInjection {

        @Test
        @DisplayName("特征→被保人映射 → FeatureExtractionResult 包含对应 insured")
        void insuredMapping() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("AGE", 35)));

            FeatureExtractionRequest req = new FeatureExtractionRequest();
            req.setOrder(order);
            req.setPolicyInsuredFeatureMap(Map.of("POL001", Map.of("INS001", Set.of("AGE"))));

            FeatureExtractionResult result = service.extract(req);

            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
        }
    }

    @Nested
    @DisplayName("分层并行执行")
    class LayerExecution {

        @Test
        @DisplayName("ORDER 级特征 → 结果写入 orderFeatures")
        void orderLevelFeature() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("AGE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.ORDER);

            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("AGE", "ONLINE")));

            FeatureExtractionRequest req = buildRequest(order, Set.of("AGE"));
            FeatureExtractionResult result = service.extract(req);

            assertThat(result.getOrderFeatures()).containsEntry("AGE", "ONLINE");
        }

        @Test
        @DisplayName("POLICY 级特征 → 结果写入 policyFeatures")
        void policyLevelFeature() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("SCORE", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.POLICY);

            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("POL001", Map.of("SCORE", 75)));

            FeatureExtractionRequest req = buildRequest(order, Set.of("SCORE"));
            FeatureExtractionResult result = service.extract(req);

            assertThat(result.getPolicyFeatures()).containsKey("POL001");
            assertThat(result.getPolicyFeatures().get("POL001")).containsEntry("SCORE", 75);
        }
    }

    @Nested
    @DisplayName("结果存储 — 各级别分发")
    class StoreResults {

        private FeatureExtractionRequest req(Order order, StorageLevel storageLevel, AggregationLevel agg) {
            FeatureConfig fc = createFeatureConfig("FC", CalcType.PARAM_MAPPING, agg, storageLevel);
            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            if (storageLevel == StorageLevel.ORDER) {
                when(mockHandler.execute(any(), eq(fc)))
                        .thenReturn(Map.of(FeatureConstants.ORDER_TARGET_KEY, "ONLINE"));
            } else {
                when(mockHandler.execute(any(), eq(fc))).thenReturn(Map.of("INS001", 35));
            }
            return buildRequest(order, Set.of("FC"));
        }

        @Test
        @DisplayName("StorageLevel=INSURED → 写入 insuredFeatures")
        void storeToInsured() {
            Order order = createTestOrder();
            FeatureExtractionResult result = service.extract(
                    req(order, StorageLevel.INSURED, AggregationLevel.ORDER));

            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
        }

        @Test
        @DisplayName("StorageLevel=POLICY → 写入 policyFeatures")
        void storeToPolicy() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("FC", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.POLICY);
            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc))).thenReturn(Map.of("POL001", 75));

            FeatureExtractionResult result = service.extract(buildRequest(order, Set.of("FC")));

            assertThat(result.getPolicyFeatures()).containsKey("POL001");
        }

        @Test
        @DisplayName("StorageLevel=APPLICANT → 写入 applicantFeatures")
        void storeToApplicant() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("FC", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.APPLICANT);
            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));
            when(mockHandler.execute(any(), eq(fc))).thenReturn(Map.of("POL001", 42));

            FeatureExtractionResult result = service.extract(buildRequest(order, Set.of("FC")));

            assertThat(result.getApplicantFeatures()).containsKey("POL001");
            assertThat(result.getApplicantFeatures().get("POL001")).containsKey("APP001");
        }

        @Test
        @DisplayName("StorageLevel=ORDER → 写入 orderFeatures")
        void storeToOrder() {
            Order order = createTestOrder();
            FeatureExtractionResult result = service.extract(
                    req(order, StorageLevel.ORDER, AggregationLevel.ORDER));

            assertThat(result.getOrderFeatures()).containsEntry("FC", "ONLINE");
        }
    }

    @Nested
    @DisplayName("calcType 分发")
    class HandlerDispatch {

        @Test
        @DisplayName("不支持的计算类型 → 抛出 IllegalArgumentException")
        void unsupportedCalcType() {
            Order order = createTestOrder();
            FeatureConfig fc = createFeatureConfig("FC", CalcType.EXTERNAL_API,
                    AggregationLevel.ORDER, StorageLevel.ORDER);

            when(featureConfigRepository.findByFeatureCodes(any())).thenReturn(List.of(fc));

            FeatureExtractionRequest req = buildRequest(order, Set.of("FC"));

            assertThatThrownBy(() -> service.extract(req))
                    .getRootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
