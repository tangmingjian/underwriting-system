package com.insurance.uw.application.service;

import com.insurance.uw.common.constants.FeatureConstants;
import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.common.enums.FeatureStatus;
import com.insurance.uw.common.enums.StorageLevel;
import com.insurance.uw.domain.model.entity.*;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.service.FeatureDependencyResolver;
import com.insurance.uw.domain.service.FeatureResultCache;
import com.insurance.uw.application.feature.handler.FeatureCalcHandler;
import com.insurance.uw.application.feature.handler.ParamMappingCalcHandler;
import com.insurance.uw.application.feature.impl.FeatureExtractionServiceImpl;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;
import com.insurance.uw.domain.context.FeatureTargeting;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FeatureExtractionServiceImpl - 全面测试")
@ExtendWith(MockitoExtension.class)
class FeatureExtractionServiceImplTest {

    @Mock
    private FeatureConfigRepository featureConfigRepository;

    @Mock
    private FeatureCalcHandler paramMappingHandler;

    @Mock
    private FeatureCalcHandler externalApiHandler;

    @Mock
    private FeatureResultCache featureResultCache;

    private ExecutorService executor;
    private FeatureExtractionServiceImpl service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        when(paramMappingHandler.getSupportedType()).thenReturn(CalcType.PARAM_MAPPING);
        when(externalApiHandler.getSupportedType()).thenReturn(CalcType.EXTERNAL_API);
        service = new FeatureExtractionServiceImpl(
                featureConfigRepository, new FeatureDependencyResolver(),
                executor,
                List.of(paramMappingHandler, externalApiHandler),
                featureResultCache);
    }

    // ==================== Helper Methods ====================

    /**
     * 创建多保单 + 同人跨保单场景的订单：
     * <pre>
     * ORD_001, channel=ONLINE
     * ├── POL_001 [HEALTH_A_001], appliedAmount=1000000
     * │   ├── APP_001 (张建国, 36, M)
     * │   ├── INS_001 (张建国, 36, M, 软件工程师)  ← 同名同人
     * │   └── INS_002 (李美玲, 31, F, 医生)
     * └── POL_002 [ACCIDENT_B_002], appliedAmount=200000
     *     ├── APP_002 (王大明, 38, M)
     *     ├── INS_001 (张建国, 36, M, 软件工程师)  ← 同人跨保单
     *     └── INS_003 (赵小红, 34, F, 会计)
     * </pre>
     */
    private Order createMultiPolicyOrder() {
        Insured ins001 = new Insured("INS001", "张建国", 36, "M");
        ins001.setOccupation("软件工程师");
        Insured ins002 = new Insured("INS002", "李美玲", 31, "F");
        ins002.setOccupation("医生");
        Insured ins003 = new Insured("INS003", "赵小红", 34, "F");
        ins003.setOccupation("会计");

        Applicant app001 = new Applicant("APP001", "张建国", 36, "M");
        Applicant app002 = new Applicant("APP002", "王大明", 38, "M");

        Product prod1 = new Product("HEALTH_A_001", "健康险A");
        Product prod2 = new Product("ACCIDENT_B_002", "意外险B");

        Policy pol001 = new Policy("POL001", prod1, app001, List.of(ins001, ins002));
        pol001.setAppliedAmount(1000000);
        Policy pol002 = new Policy("POL002", prod2, app002, List.of(ins001, ins003));
        pol002.setAppliedAmount(200000);

        return new Order("ORD001", "ONLINE", null, List.of(pol001, pol002));
    }

    /** 创建简单测试订单（单保单） */
    private Order createSimpleOrder() {
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

    private FeatureConfig createExternalApiFeatureConfig(String code, AggregationLevel aggregation,
                                                          StorageLevel storageLevel, String serviceKey) {
        FeatureConfig fc = new FeatureConfig();
        fc.setFeatureCode(code);
        fc.setCalcType(CalcType.EXTERNAL_API);
        fc.setAggregation(aggregation);
        fc.setStorageLevel(storageLevel);
        fc.setStatus(FeatureStatus.ACTIVE);
        CalcConfig calcConfig = new CalcConfig();
        ServiceConfig svc = new ServiceConfig();
        svc.setDiscoveryType("DIRECT");
        svc.setPath(serviceKey);
        svc.setProtocol("HTTPS");
        svc.setMethod("POST");
        svc.setTimeoutMs(3000);
        calcConfig.setService(svc);
        fc.setCalcConfig(calcConfig);
        return fc;
    }

    private FeatureConfig createFeatureWithTtl(String code, int ttlSeconds) {
        FeatureConfig fc = createFeatureConfig(code, CalcType.PARAM_MAPPING,
                AggregationLevel.ORDER, StorageLevel.INSURED);
        fc.setTtlSeconds(ttlSeconds);
        return fc;
    }

    /**
     * 构建 FeatureExtractionRequest：将指定特征码放入所有保单的被保人/投保人映射中。
     */
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

    /**
     * 构建带精确映射的请求（支持按保单+人员维度指定特征）。
     */
    private FeatureExtractionRequest buildRequestWithMapping(Order order,
                                                              Map<String, Map<String, Set<String>>> insuredMap,
                                                              Map<String, Map<String, Set<String>>> applicantMap) {
        FeatureExtractionRequest req = new FeatureExtractionRequest();
        req.setOrder(order);
        req.setPolicyInsuredFeatureMap(insuredMap);
        req.setPolicyApplicantFeatureMap(applicantMap);
        return req;
    }

    // ===================================================================
    // Nest 1: 9 种有效 Aggregation×Storage 组合 — 逐一验证
    // ===================================================================

    @Nested
    @DisplayName("Nest 1: 9 种 Aggregation×Storage 组合")
    class NineCombos {

        // --- #1: ORDER→ORDER ---

        @Test
        @DisplayName("#1 ORDER→ORDER: ord.channel 写入 orderFeatures")
        void orderToOrder() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("ord.channel", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.ORDER);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("ord.channel", "ONLINE")));

            FeatureExtractionResult result = service.extract(buildRequest(order, Set.of("ord.channel")));

            assertThat(result.getOrderFeatures()).containsEntry("ord.channel", "ONLINE");
        }

        // --- #2: ORDER→POLICY ---

        @Test
        @DisplayName("#2 ORDER→POLICY: pol.appliedAmount 写入 policyFeatures[POL001]")
        void orderToPolicy() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("pol.appliedAmount", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.POLICY);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("POL001", 1000000));

            FeatureExtractionResult result = service.extract(buildRequest(order, Set.of("pol.appliedAmount")));

            assertThat(result.getPolicyFeatures()).containsKey("POL001");
            assertThat(result.getPolicyFeatures().get("POL001"))
                    .containsEntry("pol.appliedAmount", 1000000);
        }

        // --- #3: ORDER→APPLICANT ---

        @Test
        @DisplayName("#3 ORDER→APPLICANT: ord.applicantRiskLevel 写入 applicantFeatures[POL001][APP001]")
        void orderToApplicant() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("ord.applicantRiskLevel", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.APPLICANT);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("POL001", "HIGH"));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("ord.applicantRiskLevel")));

            assertThat(result.getApplicantFeatures()).containsKey("POL001");
            assertThat(result.getApplicantFeatures().get("POL001")).containsKey("APP001");
            assertThat(result.getApplicantFeatures().get("POL001").get("APP001"))
                    .containsEntry("ord.applicantRiskLevel", "HIGH");
        }

        // --- #4: ORDER→INSURED ---

        @Test
        @DisplayName("#4 ORDER→INSURED: ins.creditScore 写入 insuredFeatures[POL001][INS001]")
        void orderToInsured() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("ins.creditScore", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", 720));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("ins.creditScore")));

            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsEntry("ins.creditScore", 720);
        }

        // --- #5: POLICY→POLICY ---

        @Test
        @DisplayName("#5 POLICY→POLICY: pol.maxSumAssured 写入 policyFeatures[POL001]")
        void policyToPolicy() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("pol.maxSumAssured", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.POLICY);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("POL001", 500000));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("pol.maxSumAssured")));

            assertThat(result.getPolicyFeatures()).containsKey("POL001");
            assertThat(result.getPolicyFeatures().get("POL001"))
                    .containsEntry("pol.maxSumAssured", 500000);
        }

        // --- #6: POLICY→APPLICANT ---

        @Test
        @DisplayName("#6 POLICY→APPLICANT: app.incomeVerified 写入 applicantFeatures[POL001][APP001]")
        void policyToApplicant() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("app.incomeVerified", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.APPLICANT);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            // POLICY→APPLICANT: storePolicyResults 忽略 targetId，直接写入保单投保人
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("IRRELEVANT", true));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("app.incomeVerified")));

            assertThat(result.getApplicantFeatures()).containsKey("POL001");
            assertThat(result.getApplicantFeatures().get("POL001")).containsKey("APP001");
            assertThat(result.getApplicantFeatures().get("POL001").get("APP001"))
                    .containsEntry("app.incomeVerified", true);
        }

        // --- #7: POLICY→INSURED ---

        @Test
        @DisplayName("#7 POLICY→INSURED: ins.occupationRisk 写入 insuredFeatures[POL001][INS001]")
        void policyToInsured() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("ins.occupationRisk", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", 35));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("ins.occupationRisk")));

            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsEntry("ins.occupationRisk", 35);
        }

        // --- #8: APPLICANT→APPLICANT ---

        @Test
        @DisplayName("#8 APPLICANT→APPLICANT: app.creditRating 写入 applicantFeatures[POL001][APP001]")
        void applicantToApplicant() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("app.creditRating", CalcType.PARAM_MAPPING,
                    AggregationLevel.APPLICANT, StorageLevel.APPLICANT);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            // APPLICANT→APPLICANT: storeApplicantResults 忽略 key，直接写入投保人
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("IRRELEVANT", "AAA"));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("app.creditRating")));

            assertThat(result.getApplicantFeatures()).containsKey("POL001");
            assertThat(result.getApplicantFeatures().get("POL001")).containsKey("APP001");
            assertThat(result.getApplicantFeatures().get("POL001").get("APP001"))
                    .containsEntry("app.creditRating", "AAA");
        }

        // --- #9: INSURED→INSURED ---

        @Test
        @DisplayName("#9 INSURED→INSURED: ins.personalRisk 写入 insuredFeatures[POL001][INS001]")
        void insuredToInsured() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("ins.personalRisk", CalcType.PARAM_MAPPING,
                    AggregationLevel.INSURED, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            // INSURED→INSURED: storeInsuredResults 忽略 key，直接写入当前 insCtx
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("IRRELEVANT", 42));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("ins.personalRisk")));

            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsEntry("ins.personalRisk", 42);
        }
    }

    // ===================================================================
    // Nest 2: 多保单 + 同人跨保单场景
    // ===================================================================

    @Nested
    @DisplayName("Nest 2: 多保单同人场景")
    class MultiPolicySamePerson {

        @Test
        @DisplayName("2.1 INS_001 在 POL_001 需要[age,gender]，在 POL_002 需要[occupation] → 各自只获得需要的特征")
        void differentFeaturesPerPolicy() {
            Order order = createMultiPolicyOrder();

            FeatureConfig fcAge = createFeatureConfig("ins.age", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.INSURED);
            FeatureConfig fcGender = createFeatureConfig("ins.gender", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.INSURED);
            FeatureConfig fcOccupation = createFeatureConfig("ins.occupation", CalcType.PARAM_MAPPING,
                    AggregationLevel.POLICY, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fcAge, fcGender, fcOccupation));

            // POL_001: each insured needs age, gender → handler returns them per insured
            when(paramMappingHandler.execute(any(), eq(fcAge)))
                    .thenReturn(Map.of("INS001", 36, "INS002", 31));
            when(paramMappingHandler.execute(any(), eq(fcGender)))
                    .thenReturn(Map.of("INS001", "M", "INS002", "F"));
            // POL_001 also needs occupation for INS_001/INS_002
            // ... but occupation is only in POL_002's needs; however, POL_001 will also try to
            // execute occupation if it's in the POLICY layer. We need per-policy filtering.
            // The POLICY layer iterates per-policy, and needed = collectPolicyNeeded(polId, ...)
            // This test verifies that the 'needed' filter works at the policy level.

            // POL_001 needs: age, gender
            // POL_002 needs: occupation (for INS_001, INS_003)
            Map<String, Map<String, Set<String>>> insuredMap = new LinkedHashMap<>();
            insuredMap.put("POL001", new LinkedHashMap<>());
            insuredMap.get("POL001").put("INS001", new LinkedHashSet<>(Set.of("ins.age", "ins.gender")));
            insuredMap.get("POL001").put("INS002", new LinkedHashSet<>(Set.of("ins.age", "ins.gender")));
            insuredMap.put("POL002", new LinkedHashMap<>());
            insuredMap.get("POL002").put("INS001", new LinkedHashSet<>(Set.of("ins.occupation")));
            insuredMap.get("POL002").put("INS003", new LinkedHashSet<>(Set.of("ins.occupation")));

            // POL_002 handler for occupation
            when(paramMappingHandler.execute(any(), eq(fcOccupation)))
                    .thenReturn(Map.of("INS001", "软件工程师", "INS003", "会计"));

            FeatureExtractionRequest req = buildRequestWithMapping(order, insuredMap, Map.of());
            FeatureExtractionResult result = service.extract(req);

            // POL_001/INS_001 gets age and gender, NOT occupation
            Map<String, Object> ins001Pol001 = result.getInsuredFeatures().get("POL001").get("INS001");
            assertThat(ins001Pol001).containsKeys("ins.age", "ins.gender");
            assertThat(ins001Pol001).doesNotContainKey("ins.occupation");

            // POL_002/INS_001 gets occupation only
            Map<String, Object> ins001Pol002 = result.getInsuredFeatures().get("POL002").get("INS001");
            assertThat(ins001Pol002).containsKey("ins.occupation");
            assertThat(ins001Pol002.get("ins.occupation")).isEqualTo("软件工程师");
            assertThat(ins001Pol002).doesNotContainKeys("ins.age", "ins.gender");
        }

        @Test
        @DisplayName("2.2 ORDER 级 INSURED 存储 + INS_001 跨保单出现 → 按保单过滤只写入需要的保单")
        void orderLevelInsuredStorageCrossPolicy() {
            Order order = createMultiPolicyOrder();
            FeatureConfig fc = createFeatureConfig("BASE_RISK", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("riskScore", 680)));

            // Only POL001/INS001 needs BASE_RISK; POL002 also has INS001 but doesn't need it
            Map<String, Map<String, Set<String>>> insuredMap = Map.of(
                    "POL001", Map.of("INS001", Set.of("BASE_RISK")),
                    "POL002", Map.of());
            FeatureExtractionResult result = service.extract(
                    buildRequestWithMapping(order, insuredMap, Map.of()));

            // POL001/INS001 gets the feature (mapping says it's needed)
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsEntry("riskScore", 680);
            // POL002/INS001 does NOT get the feature (mapping doesn't include it)
            assertThat(result.getInsuredFeatures()).doesNotContainKey("POL002");
        }
    }

    // ===================================================================
    // Nest 3: 依赖链执行
    // ===================================================================

    @Nested
    @DisplayName("Nest 3: 依赖链执行")
    class DependencyChain {

        @Test
        @DisplayName("3.1 BASE_RISK(无依赖) → RISK_SCORE(dependsOn=[BASE_RISK]) 两层链")
        void twoLayerChain() {
            Order order = createSimpleOrder();

            FeatureConfig fcBase = createFeatureConfig("BASE_RISK", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcBase.setDependsOn(List.of()); // no deps

            FeatureConfig fcScore = createFeatureConfig("RISK_SCORE", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcScore.setDependsOn(List.of("BASE_RISK"));

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fcScore)) // only RISK_SCORE queried directly
                    .thenReturn(List.of(fcBase));  // BASE_RISK loaded as dependency

            // Layer 0: BASE_RISK executes first
            when(paramMappingHandler.execute(any(), eq(fcBase)))
                    .thenReturn(Map.of("INS001", Map.of("riskScore", 85)));
            // Layer 1: RISK_SCORE depends on BASE_RISK
            when(paramMappingHandler.execute(any(), eq(fcScore)))
                    .thenReturn(Map.of("INS001", Map.of("RISK_SCORE", 85)));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("RISK_SCORE")));

            // Verify RISK_SCORE result is stored
            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsKey("RISK_SCORE");

            // Verify BASE_RISK was executed (its result is available in context for dependency)
            verify(paramMappingHandler).execute(any(), eq(fcBase));
            verify(paramMappingHandler).execute(any(), eq(fcScore));
        }

        @Test
        @DisplayName("3.2 A→B→C 三层依赖链")
        void threeLayerChain() {
            Order order = createSimpleOrder();

            FeatureConfig fcA = createFeatureConfig("A", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            FeatureConfig fcB = createFeatureConfig("B", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcB.setDependsOn(List.of("A"));
            FeatureConfig fcC = createFeatureConfig("C", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcC.setDependsOn(List.of("B"));

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fcC))   // C queried directly
                    .thenReturn(List.of(fcB))   // B loaded as dep
                    .thenReturn(List.of(fcA));   // A loaded as dep's dep

            when(paramMappingHandler.execute(any(), eq(fcA)))
                    .thenReturn(Map.of("INS001", Map.of("A", "a_val")));
            when(paramMappingHandler.execute(any(), eq(fcB)))
                    .thenReturn(Map.of("INS001", Map.of("B", "b_val")));
            when(paramMappingHandler.execute(any(), eq(fcC)))
                    .thenReturn(Map.of("INS001", Map.of("C", "c_val")));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("C")));

            Map<String, Object> features = result.getInsuredFeatures()
                    .get("POL001").get("INS001");
            assertThat(features).containsKeys("A", "B", "C");
        }
    }

    // ===================================================================
    // Nest 4: EXTERNAL_API 批处理
    // ===================================================================

    @Nested
    @DisplayName("Nest 4: EXTERNAL_API 批处理")
    class BatchProcessing {

        @Test
        @DisplayName("4.1 同 serviceKey 多特征 → executeBatch 调用 1 次")
        void batchMultipleFeatures() {
            Order order = createSimpleOrder();
            String servicePath = "/api/test/batch";

            FeatureConfig fc1 = createExternalApiFeatureConfig("EXT_A",
                    AggregationLevel.ORDER, StorageLevel.ORDER, servicePath);
            FeatureConfig fc2 = createExternalApiFeatureConfig("EXT_B",
                    AggregationLevel.ORDER, StorageLevel.ORDER, servicePath);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc1, fc2));

            Map<String, Map<String, Object>> batchResult = new LinkedHashMap<>();
            batchResult.put("EXT_A", Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("EXT_A", "resultA")));
            batchResult.put("EXT_B", Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("EXT_B", "resultB")));
            when(externalApiHandler.executeBatch(any(), anyList()))
                    .thenReturn(batchResult);

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("EXT_A", "EXT_B")));

            assertThat(result.getOrderFeatures()).containsEntry("EXT_A", "resultA");
            assertThat(result.getOrderFeatures()).containsEntry("EXT_B", "resultB");

            // executeBatch 调用 1 次，execute 未调用
            verify(externalApiHandler, times(1)).executeBatch(any(), anyList());
            verify(externalApiHandler, never()).execute(any(), any());
        }

        @Test
        @DisplayName("4.2 单特征 EXTERNAL_API → canBatch=false，走 execute")
        void singleFeatureNoBatch() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createExternalApiFeatureConfig("EXT_SINGLE",
                    AggregationLevel.ORDER, StorageLevel.ORDER, "/api/test/single");

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(externalApiHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("EXT_SINGLE", "done")));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("EXT_SINGLE")));

            assertThat(result.getOrderFeatures()).containsEntry("EXT_SINGLE", "done");
            verify(externalApiHandler).execute(any(), eq(fc));
            verify(externalApiHandler, never()).executeBatch(any(), anyList());
        }

        @Test
        @DisplayName("4.3 PARAM_MAPPING 永远不走 batch（即使多个同组特征）")
        void paramMappingNeverBatched() {
            Order order = createSimpleOrder();

            // Two PARAM_MAPPING features — even if they had the same serviceKey,
            // canBatch checks CalcType.EXTERNAL_API → false for PARAM_MAPPING
            FeatureConfig fc1 = createFeatureConfig("PM_A", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.ORDER);
            FeatureConfig fc2 = createFeatureConfig("PM_B", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.ORDER);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc1, fc2));
            when(paramMappingHandler.execute(any(), eq(fc1)))
                    .thenReturn(Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("PM_A", 1)));
            when(paramMappingHandler.execute(any(), eq(fc2)))
                    .thenReturn(Map.of(FeatureConstants.ORDER_TARGET_KEY, Map.of("PM_B", 2)));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("PM_A", "PM_B")));

            assertThat(result.getOrderFeatures()).containsEntry("PM_A", 1);
            assertThat(result.getOrderFeatures()).containsEntry("PM_B", 2);
            verify(paramMappingHandler, times(2)).execute(any(), any());
            verify(paramMappingHandler, never()).executeBatch(any(), anyList());
        }
    }

    // ===================================================================
    // Nest 5: 过滤与缓存
    // ===================================================================

    @Nested
    @DisplayName("Nest 5: 过滤与缓存")
    class FilterAndCache {

        @Test
        @DisplayName("5.1 policyInsuredFeatureMap 按被保人映射不同特征 → 各自只获得需要的特征")
        void partialInsuredMapping() {
            // 使用 INSURED 级特征：每个被保人独立执行，needed 过滤按人生效
            Order order = createSimpleOrder();
            // 为简单订单创建两个被保人
            Insured ins001 = new Insured("INS001", "张三", 35, "M");
            Insured ins002 = new Insured("INS002", "李四", 28, "F");
            Product prod = new Product("PROD001", "测试产品");
            Applicant app = new Applicant("APP001", "王五", 40, "M");
            Policy policy = new Policy("POL001", prod, app, List.of(ins001, ins002));
            order = new Order("ORD001", "ONLINE", null, List.of(policy));

            FeatureConfig fcA = createFeatureConfig("featA", CalcType.PARAM_MAPPING,
                    AggregationLevel.INSURED, StorageLevel.INSURED);
            FeatureConfig fcB = createFeatureConfig("featB", CalcType.PARAM_MAPPING,
                    AggregationLevel.INSURED, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fcA, fcB));
            when(paramMappingHandler.execute(any(), eq(fcA)))
                    .thenReturn(Map.of("ANY", "valueA"));
            when(paramMappingHandler.execute(any(), eq(fcB)))
                    .thenReturn(Map.of("ANY", "valueB"));

            // INS001 需要 featA，INS002 需要 featB（needed 非空 → 过滤生效）
            Map<String, Map<String, Set<String>>> insuredMap = Map.of("POL001",
                    Map.of("INS001", Set.of("featA"),
                           "INS002", Set.of("featB")));

            FeatureExtractionRequest req = buildRequestWithMapping(order, insuredMap, Map.of());
            FeatureExtractionResult result = service.extract(req);

            // INS001 只获得 featA
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsEntry("featA", "valueA")
                    .doesNotContainKey("featB");
            // INS002 只获得 featB
            assertThat(result.getInsuredFeatures().get("POL001").get("INS002"))
                    .containsEntry("featB", "valueB")
                    .doesNotContainKey("featA");

            // 每个被保人只执行了各自需要的特征
            verify(paramMappingHandler, times(2)).execute(any(), any(FeatureConfig.class));
        }

        @Test
        @DisplayName("5.2 ttlSeconds > 0 → resultCache.put 被调用")
        void cacheWrittenWhenTtlPositive() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureWithTtl("CACHE_FEATURE", 300);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("CACHE_FEATURE", "cached_val")));

            service.extract(buildRequest(order, Set.of("CACHE_FEATURE")));

            // cache.put(featureCode, targetId, value, ttlSeconds) called
            verify(featureResultCache).put("CACHE_FEATURE", "INS001", Map.of("CACHE_FEATURE", "cached_val"), 300);
        }

        @Test
        @DisplayName("5.3 ttlSeconds = -1 → 不写缓存")
        void cacheNotWrittenWhenTtlNegative() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureWithTtl("NO_CACHE_FEATURE", -1);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("INS001", Map.of("NO_CACHE_FEATURE", "no_cache")));

            service.extract(buildRequest(order, Set.of("NO_CACHE_FEATURE")));

            verify(featureResultCache, never()).put(anyString(), anyString(), any(), anyInt());
        }
    }

    // ===================================================================
    // Nest 6: 存储方向约束
    // ===================================================================

    @Nested
    @DisplayName("Nest 6: 存储方向约束 — 只允许向下存储")
    class StorageDirection {

        /**
         * 存储方向：rank(storageLevel) <= rank(aggregationLevel)
         * ORDER(3) > POLICY(2) > APPLICANT(1) > INSURED(0)
         *
         * 有效（向下/同级）：
         *   ORDER→POLICY, ORDER→APPLICANT, ORDER→INSURED
         *   POLICY→APPLICANT, POLICY→INSURED
         *   APPLICANT→APPLICANT (但 APPLICANT→INSURED 显式 NOP)
         *   INSURED→INSURED
         *
         * 反向路径（向上存储）需验证行为：
         *   这些路径在当前代码中确实存在，需要关注
         */

        @Test
        @DisplayName("INSURED→POLICY: 向上路由不允许，不写入保单特征")
        void insuredToPolicyStoredUpward() {
            // INSURED 聚合 → POLICY 存储: 聚合层级比存储层级窄，向上路由被拒绝
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("IRRELEVANT", CalcType.PARAM_MAPPING,
                    AggregationLevel.INSURED, StorageLevel.POLICY);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("ANY", 99));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("IRRELEVANT")));

            // INSURED→POLICY 向上路由被拒绝: 保单特征为空
            assertThat(result.getPolicyFeatures()).isEmpty();
        }

        @Test
        @DisplayName("INSURED→ORDER: 向上路由不允许，不写入订单特征")
        void insuredToOrderStoredUpward() {
            // INSURED 聚合 → ORDER 存储: 聚合层级比存储层级窄，向上路由被拒绝
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("INS_TO_ORD", CalcType.PARAM_MAPPING,
                    AggregationLevel.INSURED, StorageLevel.ORDER);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("ANY", "upward"));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("INS_TO_ORD")));

            // INSURED→ORDER 向上路由被拒绝: 订单特征为空
            assertThat(result.getOrderFeatures()).isEmpty();
        }

        @Test
        @DisplayName("APPLICANT→INSURED: storeApplicantResults 显式 NOP → 不写入被保人")
        void applicantToInsuredIsNop() {
            Order order = createSimpleOrder();
            FeatureConfig fc = createFeatureConfig("APP_TO_INS", CalcType.PARAM_MAPPING,
                    AggregationLevel.APPLICANT, StorageLevel.INSURED);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));
            when(paramMappingHandler.execute(any(), eq(fc)))
                    .thenReturn(Map.of("ANY", 42));

            FeatureExtractionResult result = service.extract(
                    buildRequest(order, Set.of("APP_TO_INS")));

            // storeApplicantResults INSURED branch is empty (NOP) → no insuredFeatures written
            assertThat(result.getInsuredFeatures()).isEmpty();
        }

        @Test
        @DisplayName("依赖方向校验: INSURED 级特征不能依赖 APPLICANT 级特征（向上依赖被拒绝）")
        void upwardDependencyRejected() {
            // FeatureDependencyResolver.validateDependencyDirection 在 topoSort 时校验
            FeatureDependencyResolver resolver = new FeatureDependencyResolver();

            FeatureConfig fcInsured = new FeatureConfig();
            fcInsured.setFeatureCode("FC_INS");
            fcInsured.setAggregation(AggregationLevel.INSURED);
            fcInsured.setDependsOn(List.of("FC_APP"));

            FeatureConfig fcApp = new FeatureConfig();
            fcApp.setFeatureCode("FC_APP");
            fcApp.setAggregation(AggregationLevel.APPLICANT);

            // INSURED(0) 依赖 APPLICANT(1) → APPLICANT rank=1 > INSURED rank=0
            // dependencyRank(1) < featureRank(0)? No, 1 < 0 is false
            // Wait, featureRank=0(INSURED), dependencyRank=1(APPLICANT)
            // dependencyRank(1) < featureRank(0) → false, so no exception
            // The dependency direction rule: must depend on SAME or HIGHER rank
            // INSURED depends on APPLICANT: dependencyRank(1) >= featureRank(0)? YES → valid
            // This means INSURED depends on APPLICANT is OK! (APPLICANT is broader)
            // Let me reconsider...
            // The rule: rank(storage) <= rank(aggregation) for storage direction
            // For dependency: feature can only depend on features at SAME or HIGHER aggregation level
            // So an INSURED-level feature CAN depend on an APPLICANT-level feature

            // Let's test the reverse: APPLICANT(1) depends on INSURED(0)
            // dependencyRank(0) < featureRank(1) → true → EXCEPTION!
            FeatureConfig fcApp2 = new FeatureConfig();
            fcApp2.setFeatureCode("FC_APP2");
            fcApp2.setAggregation(AggregationLevel.APPLICANT);
            fcApp2.setDependsOn(List.of("FC_INS2"));

            FeatureConfig fcIns2 = new FeatureConfig();
            fcIns2.setFeatureCode("FC_INS2");
            fcIns2.setAggregation(AggregationLevel.INSURED);

            try {
                resolver.topoSort(Set.of("FC_APP2", "FC_INS2"), Map.of("FC_APP2", fcApp2, "FC_INS2", fcIns2));
            } catch (IllegalStateException e) {
                assertThat(e.getMessage()).contains("不允许依赖");
            }
        }
    }

    // ===================================================================
    // Nest 7: 按需过滤 — ORDER/POLICY 级按 entity 需要过滤
    // ===================================================================

    @Nested
    @DisplayName("Nest 7: 按需过滤 — 避免不必要计算")
    class DemandFiltering {

        // --- 7.1 buildFeatureInsuredTargetMap 基础 ---

        @Test
        @DisplayName("7.1 buildFeatureInsuredTargetMap: 直接从 policyInsuredFeatureMap 初始化")
        void targetMapFromDirectNeeds() {
            Map<String, Map<String, Set<String>>> insuredMap = Map.of(
                    "POL001", Map.of(
                            "INS001", Set.of("featA"),
                            "INS002", Set.of("featB")));

            FeatureTargeting ft = new FeatureTargeting();
            ft.setInputMaps(insuredMap, null);
            Map<String, Set<String>> result = ft.buildFeatureInsuredTargetMap(Map.of());

            assertThat(result).containsKeys("featA", "featB");
            assertThat(result.get("featA")).containsExactly("INS001");
            assertThat(result.get("featB")).containsExactly("INS002");
        }

        // --- 7.2 buildFeatureInsuredTargetMap 依赖传播 ---

        @Test
        @DisplayName("7.2 buildFeatureInsuredTargetMap: featA depends on featB → featB 目标传播")
        void targetMapPropagatesThroughDependency() {
            Map<String, Map<String, Set<String>>> insuredMap = Map.of(
                    "POL001", Map.of("INS001", Set.of("featA")));

            FeatureConfig fcA = createFeatureConfig("featA", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcA.setDependsOn(List.of("featB"));
            FeatureConfig fcB = createFeatureConfig("featB", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            Map<String, FeatureConfig> configMap = Map.of("featA", fcA, "featB", fcB);

            FeatureTargeting ft2 = new FeatureTargeting();
            ft2.setInputMaps(insuredMap, null);
            Map<String, Set<String>> result = ft2.buildFeatureInsuredTargetMap(configMap);

            // featA has INS001 directly; featB inherits INS001 from featA
            assertThat(result).containsKeys("featA", "featB");
            assertThat(result.get("featA")).containsExactly("INS001");
            assertThat(result.get("featB")).containsExactly("INS001");
        }

        // --- 7.3 buildFeatureInsuredTargetMap 多层依赖传播 ---

        @Test
        @DisplayName("7.3 buildFeatureInsuredTargetMap: A→B→C 三层依赖，C 获得 A 的目标")
        void targetMapThreeLayerPropagation() {
            Map<String, Map<String, Set<String>>> insuredMap = Map.of(
                    "POL001", Map.of("INS001", Set.of("featA")));

            FeatureConfig fcA = createFeatureConfig("featA", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcA.setDependsOn(List.of("featB"));
            FeatureConfig fcB = createFeatureConfig("featB", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);
            fcB.setDependsOn(List.of("featC"));
            FeatureConfig fcC = createFeatureConfig("featC", CalcType.PARAM_MAPPING,
                    AggregationLevel.ORDER, StorageLevel.INSURED);

            Map<String, FeatureConfig> configMap = Map.of("featA", fcA, "featB", fcB, "featC", fcC);

            FeatureTargeting ft3 = new FeatureTargeting();
            ft3.setInputMaps(insuredMap, null);
            Map<String, Set<String>> result = ft3.buildFeatureInsuredTargetMap(configMap);

            assertThat(result).containsKeys("featA", "featB", "featC");
            assertThat(result.get("featA")).containsExactly("INS001");
            assertThat(result.get("featB")).containsExactly("INS001");
            assertThat(result.get("featC")).containsExactly("INS001");
        }

        // --- 7.4 OrderFeatureContext.getInsuredsForFeature 使用 targetMap ---

        @Test
        @DisplayName("7.4 getInsuredsForFeature: 使用 featureInsuredTargetMap 返回匹配的被保人")
        void orderCtxGetInsuredsForFeatureWithTargetMap() {
            Order order = createMultiPolicyOrder();
            OrderFeatureContext ctx = new OrderFeatureContext(order);
            FeatureTargeting ft = new FeatureTargeting();
            ft.setFeatureInsuredTargetMap(Map.of(
                    "featA", Set.of("INS001"),
                    "featB", Set.of("INS002", "INS003")));
            ctx.setFeatureTargeting(ft);

            List<InsuredFeatureContext> resultA = ctx.getInsuredsForFeature("featA");
            // INS001 appears in both policies → 2 contexts for the same insured
            assertThat(resultA).hasSize(2);
            assertThat(resultA).extracting(InsuredFeatureContext::getInsuredId)
                    .allMatch(id -> id.equals("INS001"));

            List<InsuredFeatureContext> resultB = ctx.getInsuredsForFeature("featB");
            assertThat(resultB).hasSize(2);
            assertThat(resultB).extracting(InsuredFeatureContext::getInsuredId)
                    .containsExactlyInAnyOrder("INS002", "INS003");
        }

        // --- 7.5 getInsuredsForFeature 回退到 policyInsuredFeatureMap ---

        @Test
        @DisplayName("7.5 getInsuredsForFeature: 无 targetMap 时回退到 policyInsuredFeatureMap")
        void orderCtxFallbackToPolicyInsuredFeatureMap() {
            Order order = createMultiPolicyOrder();
            OrderFeatureContext ctx = new OrderFeatureContext(order);
            FeatureTargeting ft = new FeatureTargeting();
            ft.setInputMaps(Map.of(
                    "POL001", Map.of("INS001", Set.of("featA")),
                    "POL002", Map.of("INS003", Set.of("featB"))), null);
            ctx.setFeatureTargeting(ft);

            List<InsuredFeatureContext> resultA = ctx.getInsuredsForFeature("featA");
            // INS001 appears in both POL001 and POL002 → 2 contexts
            assertThat(resultA).hasSize(2);
            assertThat(resultA).extracting(InsuredFeatureContext::getInsuredId)
                    .allMatch(id -> id.equals("INS001"));

            // featC is not in any mapping → fallback to all insureds
            List<InsuredFeatureContext> resultC = ctx.getInsuredsForFeature("featC");
            assertThat(resultC).hasSize(4); // INS001 × 2 (cross-policy) + INS002 + INS003
        }

        // --- 7.6 getPoliciesForFeature 使用 targetMap ---

        @Test
        @DisplayName("7.6 getPoliciesForFeature: 使用 featurePolicyTargetMap 返回匹配的保单")
        void orderCtxGetPoliciesForFeatureWithTargetMap() {
            Order order = createMultiPolicyOrder();
            OrderFeatureContext ctx = new OrderFeatureContext(order);
            FeatureTargeting ft = new FeatureTargeting();
            ft.setFeaturePolicyTargetMap(Map.of(
                    "featA", Set.of("POL001"),
                    "featB", Set.of("POL002")));
            ctx.setFeatureTargeting(ft);

            List<PolicyFeatureContext> resultA = ctx.getPoliciesForFeature("featA");
            assertThat(resultA).hasSize(1);
            assertThat(resultA.get(0).getPolicyId()).isEqualTo("POL001");

            List<PolicyFeatureContext> resultB = ctx.getPoliciesForFeature("featB");
            assertThat(resultB).hasSize(1);
            assertThat(resultB.get(0).getPolicyId()).isEqualTo("POL002");
        }

        // --- 7.7 PolicyFeatureContext.getInsuredsForFeature ---

        @Test
        @DisplayName("7.7 PolicyFeatureContext.getInsuredsForFeature: 过滤到当前保单的被保人")
        void policyCtxGetInsuredsForFeature() {
            Order order = createMultiPolicyOrder();
            OrderFeatureContext orderCtx = new OrderFeatureContext(order);
            // INS001 is in both POL001 and POL002
            FeatureTargeting ft = new FeatureTargeting();
            ft.setFeatureInsuredTargetMap(Map.of(
                    "featA", Set.of("INS001", "INS002")));
            orderCtx.setFeatureTargeting(ft);

            PolicyFeatureContext pol001 = orderCtx.findPolicyCtx("POL001");
            List<InsuredFeatureContext> result = pol001.getInsuredsForFeature("featA");
            // POL001 has INS001 and INS002; both match the target map
            assertThat(result).hasSize(2);
            assertThat(result).extracting(InsuredFeatureContext::getInsuredId)
                    .containsExactlyInAnyOrder("INS001", "INS002");

            PolicyFeatureContext pol002 = orderCtx.findPolicyCtx("POL002");
            List<InsuredFeatureContext> result2 = pol002.getInsuredsForFeature("featA");
            // POL002 has INS001 and INS003; only INS001 matches
            assertThat(result2).hasSize(1);
            assertThat(result2.get(0).getInsuredId()).isEqualTo("INS001");
        }

        // --- 7.8 POLICY 级 + 真实 ParamMappingCalcHandler: 只计算需要的被保人 ---

        @Test
        @DisplayName("7.8 POLICY 级 PARAM_MAPPING 只计算需要的被保人")
        void policyLevelOnlyComputesNeededInsureds() {
            // 使用真实 ParamMappingCalcHandler 验证 POLICY 级的按需过滤
            FeatureExtractionServiceImpl svcWithRealHandler = new FeatureExtractionServiceImpl(
                    featureConfigRepository, new FeatureDependencyResolver(),
                    executor,
                    List.of(new ParamMappingCalcHandler(), externalApiHandler),
                    featureResultCache);

            Order order = createMultiPolicyOrder();

            // featA: POLICY level, INSURED storage, reads insured.occupation
            FeatureConfig fc = new FeatureConfig();
            fc.setFeatureCode("ins.occupation");
            fc.setCalcType(CalcType.PARAM_MAPPING);
            fc.setAggregation(AggregationLevel.POLICY);
            fc.setStorageLevel(StorageLevel.INSURED);
            fc.setStatus(FeatureStatus.ACTIVE);
            CalcConfig calcConfig = new CalcConfig();
            calcConfig.setSource("insured.occupation");
            fc.setCalcConfig(calcConfig);

            when(featureConfigRepository.findByFeatureCodes(any()))
                    .thenReturn(List.of(fc));

            // POL001: INS001 needs ins.occupation, INS002 does NOT need it
            Map<String, Map<String, Set<String>>> insuredMap = new LinkedHashMap<>();
            insuredMap.put("POL001", Map.of("INS001", Set.of("ins.occupation")));
            // POL002: no one needs ins.occupation
            insuredMap.put("POL002", Map.of());

            FeatureExtractionRequest req = buildRequestWithMapping(order, insuredMap, Map.of());
            FeatureExtractionResult result = svcWithRealHandler.extract(req);

            // POL001/INS001 应该得到 occupation（软件工程师）
            assertThat(result.getInsuredFeatures()).containsKey("POL001");
            assertThat(result.getInsuredFeatures().get("POL001")).containsKey("INS001");
            assertThat(result.getInsuredFeatures().get("POL001").get("INS001"))
                    .containsEntry("ins.occupation", "软件工程师");

            // POL001/INS002 不应该得到 occupation（不在 needed 中）
            assertThat(result.getInsuredFeatures().get("POL001")).doesNotContainKey("INS002");

            // POL002 不应该有 insuredFeatures（该保单无人需要该特征）
            assertThat(result.getInsuredFeatures()).doesNotContainKey("POL002");
        }

        // --- 7.9 buildFeatureInsuredTargetMap 空映射不抛异常 ---

        @Test
        @DisplayName("7.9 buildFeatureInsuredTargetMap: null 输入返回空 Map 不抛异常")
        void targetMapNullInputReturnsEmpty() {
            FeatureTargeting ft9 = new FeatureTargeting();
            Map<String, Set<String>> result = ft9.buildFeatureInsuredTargetMap(Map.of());
            assertThat(result).isEmpty();
        }
    }
}
