package com.insurance.uw.feature.core.routing;

import com.insurance.uw.common.enums.AggregationLevel;
import com.insurance.uw.common.enums.StorageLevel;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.FeatureTargeting;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.FeatureConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 统一特征结果分发器：根据 AggregationLevel × StorageLevel 将 handler 输出路由到正确的上下文。
 *
 * <h3>有效路由矩阵（只允许向下/同级存储）</h3>
 * <pre>
 * agg \ storage | ORDER | POLICY | APPLICANT | INSURED
 * --------------+-------+--------+-----------+--------
 * ORDER         |  ✓    |   ✓    |    ✓      |   ✓
 * POLICY        |  -    |   ✓    |    ✓      |   ✓
 * APPLICANT     |  -    |   -    |    ✓      |   -
 * INSURED       |  -    |   -    |    -      |   ✓
 * </pre>
 * 其中 "-" 表示向上路由被拒绝（聚合层级比存储层级窄，NOP）。
 *
 * <p>跨保单过滤：ORDER→INSURED 和 ORDER→APPLICANT 路径会查询 {@link FeatureTargeting}
 * 的 featureInsuredPolicyMap / featurePolicyTargetMap，确保结果只写入需要该特征的 (policyId, entityId) 对。</p>
 */
public class FeatureResultDispatcher {

    private static final Logger LOG = Logger.getLogger(FeatureResultDispatcher.class.getName());

    private final OrderFeatureContext orderCtx;
    private final FeatureTargeting targeting;

    public FeatureResultDispatcher(OrderFeatureContext orderCtx, FeatureTargeting targeting) {
        this.orderCtx = orderCtx;
        this.targeting = targeting;
    }

    // ==================== Public API ====================

    /**
     * 主入口：将 handler 结果分发到正确的上下文。
     *
     * @param aggCtx  计算上下文（OrderFeatureContext / PolicyFeatureContext /
     *                InsuredFeatureContext / ApplicantFeatureContext）
     * @param fc      特征配置，携带 AggregationLevel + StorageLevel
     * @param results handler 输出：Map&lt;targetKey, featureValue&gt;
     *                targetKey 约定：ORDER_KEY | insuredId | policyId | SELF_KEY
     */
    public void dispatch(Object aggCtx, FeatureConfig fc, Map<String, Object> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        AggregationLevel agg = fc.getAggregation();
        StorageLevel storage = fc.getStorageLevel();

        switch (agg) {
            case ORDER    -> dispatchOrderResults((OrderFeatureContext) aggCtx, fc, storage, results);
            case POLICY   -> dispatchPolicyResults((PolicyFeatureContext) aggCtx, fc, storage, results);
            case INSURED  -> dispatchInsuredResults((InsuredFeatureContext) aggCtx, fc, storage, results);
            case APPLICANT -> dispatchApplicantResults((ApplicantFeatureContext) aggCtx, fc, storage, results);
        }
    }

    /**
     * 批量分发：逐个特征结果调用 dispatch。
     */
    public void dispatchBatch(Object aggCtx, Map<FeatureConfig, Map<String, Object>> batchResults) {
        for (var entry : batchResults.entrySet()) {
            dispatch(aggCtx, entry.getKey(), entry.getValue());
        }
    }

    // ==================== ORDER Aggregation (向下可到任意层) ====================

    private void dispatchOrderResults(OrderFeatureContext ctx, FeatureConfig fc,
                                      StorageLevel storage, Map<String, Object> results) {
        switch (storage) {
            case ORDER     -> dispatchOrderToOrder(ctx, fc, results);
            case POLICY    -> dispatchOrderToPolicy(ctx, fc, results);
            case APPLICANT -> dispatchOrderToApplicant(ctx, fc, results);
            case INSURED   -> dispatchOrderToInsured(ctx, fc, results);
        }
    }

    /** ORDER × ORDER: 写入订单级特征。targetId 无关。 */
    private void dispatchOrderToOrder(OrderFeatureContext ctx, FeatureConfig fc,
                                      Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            ctx.getOrderFeatures().putAll(featureMap);
            LOG.info("[存储] ORDER×ORDER: " + fc.getFeatureCode() + "=" + featureMap
                    + " → order");
            return;
        }
    }

    /**
     * ORDER × POLICY: targetId = policyId。写入目标保单的 policyFeatures。
     * 若 policyId 在上下文中找不到对应保单，静默跳过（该保单不需要此特征）。
     */
    private void dispatchOrderToPolicy(OrderFeatureContext ctx, FeatureConfig fc,
                                       Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            String policyId = entry.getKey();
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            PolicyFeatureContext polCtx = ctx.findPolicyCtx(policyId);
            if (polCtx != null) {
                polCtx.getPolicyFeatures().putAll(featureMap);
                LOG.info("[存储] ORDER×POLICY: " + fc.getFeatureCode() + "=" + featureMap
                        + " → policyId=" + policyId);
            }
        }
    }

    /**
     * ORDER × APPLICANT: targetId = policyId。写入目标保单的投保人特征。
     * 通过 FeatureTargeting 过滤：只写入 featurePolicyTargetMap 中标记的保单。
     */
    private void dispatchOrderToApplicant(OrderFeatureContext ctx, FeatureConfig fc,
                                          Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            String policyId = entry.getKey();
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            PolicyFeatureContext polCtx = ctx.findPolicyCtx(policyId, fc.getFeatureCode());
            if (polCtx != null && polCtx.getApplicantCtx() != null) {
                String applicantId = polCtx.getApplicantCtx().getApplicantId();
                polCtx.getApplicantCtx().getFeatures().putAll(featureMap);
                LOG.info("[存储] ORDER×APPLICANT: " + fc.getFeatureCode() + "=" + featureMap
                        + " → policyId=" + policyId + " applicantId=" + applicantId);
            }
        }
    }

    /**
     * ORDER × INSURED（最复杂路径）: targetId = insuredId。
     * 通过 findInsuredCtx(insuredId, featureCode) 内部查询 featureInsuredPolicyMap，
     * 同一被保人出现在多个保单中时，只写入有针对性的那些保单。
     */
    private void dispatchOrderToInsured(OrderFeatureContext ctx, FeatureConfig fc,
                                        Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            String insuredId = entry.getKey();
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            List<InsuredFeatureContext> targets = ctx.findInsuredCtx(insuredId, fc.getFeatureCode());
            if (targets.isEmpty()) {
                LOG.fine("[存储] ORDER×INSURED: " + fc.getFeatureCode()
                        + " insuredId=" + insuredId + " 无匹配保单，跳过");
            }
            for (InsuredFeatureContext insCtx : targets) {
                String policyId = insCtx.getPolicyContext().getPolicyId();
                insCtx.getAcquiredFeatures().putAll(featureMap);
                LOG.info("[存储] ORDER×INSURED: " + fc.getFeatureCode() + "=" + featureMap
                        + " → policyId=" + policyId + " insuredId=" + insuredId);
            }
        }
    }

    // ==================== POLICY Aggregation (向下可到 APPLICANT/INSURED，不可到 ORDER) ====================

    private void dispatchPolicyResults(PolicyFeatureContext polCtx, FeatureConfig fc,
                                       StorageLevel storage, Map<String, Object> results) {
        switch (storage) {
            case POLICY    -> dispatchPolicyToPolicy(polCtx, fc, results);
            case APPLICANT -> dispatchPolicyToApplicant(polCtx, fc, results);
            case INSURED   -> dispatchPolicyToInsured(polCtx, fc, results);
            case ORDER -> {
                // POLICY → ORDER: 向上路由拒绝
                LOG.warning("[存储] 拒绝(向上): POLICY×ORDER " + fc.getFeatureCode()
                        + " policyId=" + polCtx.getPolicyId());
            }
        }
    }

    /** POLICY × POLICY: 写入当前保单的 policyFeatures。targetId 无关。 */
    private void dispatchPolicyToPolicy(PolicyFeatureContext polCtx, FeatureConfig fc,
                                        Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            polCtx.getPolicyFeatures().putAll(featureMap);
            LOG.info("[存储] POLICY×POLICY: " + fc.getFeatureCode() + "=" + featureMap
                    + " → policyId=" + polCtx.getPolicyId());
            return;
        }
    }

    /** POLICY × APPLICANT: 写入当前保单的投保人特征。targetId 无关。 */
    private void dispatchPolicyToApplicant(PolicyFeatureContext polCtx, FeatureConfig fc,
                                           Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            polCtx.getApplicantCtx().getFeatures().putAll(featureMap);
            LOG.info("[存储] POLICY×APPLICANT: " + fc.getFeatureCode() + "=" + featureMap
                    + " → policyId=" + polCtx.getPolicyId()
                    + " applicantId=" + polCtx.getApplicantCtx().getApplicantId());
            return;
        }
    }

    /** POLICY × INSURED: targetId = insuredId。在当前保单下精确匹配。 */
    private void dispatchPolicyToInsured(PolicyFeatureContext polCtx, FeatureConfig fc,
                                         Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            String insuredId = entry.getKey();
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            InsuredFeatureContext insCtx = polCtx.getInsureds().stream()
                    .filter(ic -> ic.getInsuredId().equals(insuredId))
                    .findFirst().orElse(null);
            if (insCtx != null) {
                insCtx.getAcquiredFeatures().putAll(featureMap);
                LOG.info("[存储] POLICY×INSURED: " + fc.getFeatureCode() + "=" + featureMap
                        + " → policyId=" + polCtx.getPolicyId() + " insuredId=" + insuredId);
            } else {
                LOG.fine("[存储] POLICY×INSURED: " + fc.getFeatureCode()
                        + " insuredId=" + insuredId + " 在当前保单中未找到");
            }
        }
    }

    // ==================== INSURED Aggregation (只能写入自身 INSURED) ====================

    private void dispatchInsuredResults(InsuredFeatureContext insCtx, FeatureConfig fc,
                                        StorageLevel storage, Map<String, Object> results) {
        switch (storage) {
            case INSURED -> dispatchInsuredToInsured(insCtx, fc, results);
            case APPLICANT -> LOG.warning("[存储] 拒绝(跨兄弟): INSURED×APPLICANT " + fc.getFeatureCode()
                    + " insuredId=" + insCtx.getInsuredId());
            case POLICY -> LOG.warning("[存储] 拒绝(向上): INSURED×POLICY " + fc.getFeatureCode()
                    + " insuredId=" + insCtx.getInsuredId());
            case ORDER -> LOG.warning("[存储] 拒绝(向上): INSURED×ORDER " + fc.getFeatureCode()
                    + " insuredId=" + insCtx.getInsuredId());
        }
    }

    /** INSURED × INSURED: 写入当前被保人的 acquiredFeatures。targetId 无关（SELF_KEY）。 */
    private void dispatchInsuredToInsured(InsuredFeatureContext insCtx, FeatureConfig fc,
                                          Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            insCtx.getAcquiredFeatures().putAll(featureMap);
            String policyId = insCtx.getPolicyContext() != null
                    ? insCtx.getPolicyContext().getPolicyId() : "?";
            LOG.info("[存储] INSURED×INSURED: " + fc.getFeatureCode() + "=" + featureMap
                    + " → policyId=" + policyId + " insuredId=" + insCtx.getInsuredId());
            return;
        }
    }

    // ==================== APPLICANT Aggregation (只能写入自身 APPLICANT) ====================

    private void dispatchApplicantResults(ApplicantFeatureContext appCtx, FeatureConfig fc,
                                          StorageLevel storage, Map<String, Object> results) {
        switch (storage) {
            case APPLICANT -> dispatchApplicantToApplicant(appCtx, fc, results);
            case INSURED -> LOG.warning("[存储] 拒绝(不可达): APPLICANT×INSURED " + fc.getFeatureCode()
                    + " applicantId=" + appCtx.getApplicantId());
            case POLICY -> LOG.warning("[存储] 拒绝(向上): APPLICANT×POLICY " + fc.getFeatureCode()
                    + " applicantId=" + appCtx.getApplicantId());
            case ORDER -> LOG.warning("[存储] 拒绝(向上): APPLICANT×ORDER " + fc.getFeatureCode()
                    + " applicantId=" + appCtx.getApplicantId());
        }
    }

    /** APPLICANT × APPLICANT: 写入当前投保人的 features。targetId 无关（SELF_KEY）。 */
    private void dispatchApplicantToApplicant(ApplicantFeatureContext appCtx, FeatureConfig fc,
                                              Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            appCtx.getFeatures().putAll(featureMap);
            String policyId = appCtx.getPolicyContext() != null
                    ? appCtx.getPolicyContext().getPolicyId() : "?";
            LOG.info("[存储] APPLICANT×APPLICANT: " + fc.getFeatureCode() + "=" + featureMap
                    + " → policyId=" + policyId + " applicantId=" + appCtx.getApplicantId());
            return;
        }
    }

    // ==================== Helper ====================

    /**
     * 将特征值统一转为 Map 形式：已是 Map 则直接返回，否则包装为 {featureCode: value}。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapFeatureValue(FeatureConfig fc, Object value) {
        return (value instanceof Map)
                ? (Map<String, Object>) value
                : Collections.singletonMap(fc.getFeatureCode(), value);
    }
}
