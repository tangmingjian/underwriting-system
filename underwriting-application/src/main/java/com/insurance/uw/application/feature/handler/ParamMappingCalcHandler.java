package com.insurance.uw.application.feature.handler;

import com.insurance.uw.common.constants.FeatureConstants;
import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.valueobject.CalcConfig;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PARAM_MAPPING 类型处理器：解析 calc_config.source → entityType.fieldName，按上下文类型分派读取实体字段值。
 */
public class ParamMappingCalcHandler implements FeatureCalcHandler {

    private static final Map<Class<?>, Map<String, Method>> GETTER_CACHE = new ConcurrentHashMap<>();

    @Override
    public CalcType getSupportedType() {
        return CalcType.PARAM_MAPPING;
    }

    @Override
    public Map<String, Object> execute(Object ctx, FeatureConfig fc) {
        CalcConfig calcConfig = fc.getCalcConfig();
        String source = calcConfig.getSource();
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("特征 " + fc.getFeatureCode() + " 的 calc_config.source 未配置");
        }

        String[] parts = source.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "特征 " + fc.getFeatureCode() + " 的 source 格式无效: " + source
                            + "，期望格式 {entityType}.{fieldName}");
        }
        String entityType = parts[0];
        String fieldName = parts[1];

        if (ctx instanceof OrderFeatureContext) {
            return executeOrderLevel((OrderFeatureContext) ctx, fc, entityType, fieldName);
        } else if (ctx instanceof PolicyFeatureContext) {
            return executePolicyLevel((PolicyFeatureContext) ctx, fc, entityType, fieldName);
        } else if (ctx instanceof InsuredFeatureContext) {
            return executeInsuredLevel((InsuredFeatureContext) ctx, fc, entityType, fieldName);
        } else if (ctx instanceof ApplicantFeatureContext) {
            return executeApplicantLevel((ApplicantFeatureContext) ctx, fc, entityType, fieldName);
        } else {
            throw new IllegalArgumentException("不支持的上下文类型: " + ctx.getClass().getName());
        }
    }

    /**
     * ORDER 级聚合：遍历整个订单树，按 entityType 读取实体字段值。
     *
     * <p>利用 getInsuredsForFeature / getPoliciesForFeature 只处理 FeatureTargeting
     * 中标记的相关实体，避免遍历不需要该特征的数据。</p>
     *
     * <p>结果 key 约定（供 FeatureResultDispatcher 路由）：</p>
     * <ul>
     *   <li>entityType=order    → key = __ORDER__（同时写入 policyId 副本支持向下路由）</li>
     *   <li>entityType=policy   → key = policyId</li>
     *   <li>entityType=insured  → key = insuredId</li>
     *   <li>entityType=applicant → key = policyId（dispatcher 通过 policyId 查找 PolicyFeatureContext）</li>
     * </ul>
     */
    private Map<String, Object> executeOrderLevel(OrderFeatureContext ctx, FeatureConfig fc,
                                                  String entityType, String fieldName) {
        Map<String, Object> result = new HashMap<>();

        switch (entityType) {
            case "order": {
                // 读取订单实体字段，同时写入 ORDER_KEY（Order→Order 路由）和 policyId（Order→Policy/Applicant 路由）
                Object value = readFieldValue(ctx.getOrder(), fieldName);
                Map<String, Object> featureVal = Collections.singletonMap(fc.getFeatureCode(), value);
                result.put(FeatureConstants.ORDER_TARGET_KEY, featureVal);
                for (PolicyFeatureContext polCtx : ctx.getPoliciesForFeature(fc.getFeatureCode())) {
                    result.put(polCtx.getPolicyId(), featureVal);
                }
                break;
            }
            case "policy": {
                // 遍历需要该特征的保单，读取各自 Policy 实体字段
                for (PolicyFeatureContext polCtx : ctx.getPoliciesForFeature(fc.getFeatureCode())) {
                    Object value = readFieldValue(polCtx.getPolicy(), fieldName);
                    result.put(polCtx.getPolicyId(), Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "insured": {
                // 遍历需要该特征的被保人，读取各自 Insured 实体字段
                for (InsuredFeatureContext insCtx : ctx.getInsuredsForFeature(fc.getFeatureCode())) {
                    Object value = readFieldValue(insCtx.getInsured(), fieldName);
                    result.put(insCtx.getInsuredId(), Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "applicant": {
                // 遍历需要该特征的保单，读取 Applicant 实体字段
                // key = policyId: FeatureResultDispatcher.dispatchOrderToApplicant 通过 policyId 查找 PolicyFeatureContext
                for (PolicyFeatureContext polCtx : ctx.getPoliciesForFeature(fc.getFeatureCode())) {
                    ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
                    if (appCtx != null && appCtx.getApplicant() != null) {
                        Object value = readFieldValue(appCtx.getApplicant(), fieldName);
                        result.put(polCtx.getPolicyId(), Collections.singletonMap(fc.getFeatureCode(), value));
                    }
                }
                break;
            }
            case "feature": {
                int dot = fieldName.indexOf('.');
                String depFeatureCode = dot > 0 ? fieldName.substring(0, dot) : fieldName;
                String subPath = dot > 0 ? fieldName.substring(dot + 1) : null;

                for (InsuredFeatureContext insCtx : ctx.getInsuredsForFeature(fc.getFeatureCode())) {
                    Object depResult = resolveFeatureFromContext(insCtx, depFeatureCode);
                    Object value = subPath != null ? readFieldValue(depResult, subPath) : depResult;
                    result.put(insCtx.getInsuredId(),
                            Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            default:
                throw new IllegalArgumentException(
                        "特征 " + fc.getFeatureCode() + " 的 entityType 无效: " + entityType
                                + "，支持: order, policy, insured, applicant, feature");
        }

        return result;
    }

    /**
     * POLICY 级聚合：通过 getInsuredsForFeature 只处理当前保单下需要该特征的被保人。
     */
    private Map<String, Object> executePolicyLevel(PolicyFeatureContext polCtx, FeatureConfig fc,
                                                   String entityType, String fieldName) {
        Map<String, Object> result = new HashMap<>();

        switch (entityType) {
            case "order": {
                OrderFeatureContext orderCtx = polCtx.getOrderContext();
                if (orderCtx != null) {
                    Object value = readFieldValue(orderCtx.getOrder(), fieldName);
                    result.put(FeatureConstants.ORDER_TARGET_KEY, Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "policy": {
                Object value = readFieldValue(polCtx.getPolicy(), fieldName);
                result.put(polCtx.getPolicyId(), Collections.singletonMap(fc.getFeatureCode(), value));
                break;
            }
            case "insured": {
                for (InsuredFeatureContext insCtx : polCtx.getInsuredsForFeature(fc.getFeatureCode())) {
                    Object value = readFieldValue(insCtx.getInsured(), fieldName);
                    result.put(insCtx.getInsuredId(), Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "applicant": {
                ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
                if (appCtx != null && appCtx.getApplicant() != null) {
                    Object value = readFieldValue(appCtx.getApplicant(), fieldName);
                    result.put(appCtx.getApplicantId(), Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "feature": {
                int dot = fieldName.indexOf('.');
                String depFeatureCode = dot > 0 ? fieldName.substring(0, dot) : fieldName;
                String subPath = dot > 0 ? fieldName.substring(dot + 1) : null;

                for (InsuredFeatureContext insCtx : polCtx.getInsuredsForFeature(fc.getFeatureCode())) {
                    Object depResult = resolveFeatureFromContext(insCtx, depFeatureCode);
                    Object value = subPath != null ? readFieldValue(depResult, subPath) : depResult;
                    result.put(insCtx.getInsuredId(),
                            Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            default:
                throw new IllegalArgumentException(
                        "特征 " + fc.getFeatureCode() + " 的 entityType 无效: " + entityType
                                + "，支持: order, policy, insured, applicant, feature");
        }

        return result;
    }

    /**
     * INSURED 级聚合：直接读取当前 Insured 实体字段值。
     * storeInsuredResults 的 INSURED 分支忽略 entry key，直接写入 insCtx.getAcquiredFeatures()。
     */
    private Map<String, Object> executeInsuredLevel(InsuredFeatureContext insCtx, FeatureConfig fc,
                                                    String entityType, String fieldName) {
        if (!"insured".equals(entityType)) {
            throw new IllegalArgumentException(
                    "INSURED 级特征 " + fc.getFeatureCode() + " 的 source.entityType 必须为 insured，实际: " + entityType);
        }
        Object value = readFieldValue(insCtx.getInsured(), fieldName);
        return Map.of(FeatureConstants.SELF_TARGET_KEY, Collections.singletonMap(fc.getFeatureCode(), value));
    }

    /**
     * APPLICANT 级聚合：直接读取当前 Applicant 实体字段值。
     * storeApplicantResults 的 APPLICANT 分支忽略 entry key，直接写入 appCtx.getFeatures()。
     */
    private Map<String, Object> executeApplicantLevel(ApplicantFeatureContext appCtx, FeatureConfig fc,
                                                      String entityType, String fieldName) {
        if (!"applicant".equals(entityType)) {
            throw new IllegalArgumentException(
                    "APPLICANT 级特征 " + fc.getFeatureCode() + " 的 source.entityType 必须为 applicant，实际: " + entityType);
        }
        Object value = readFieldValue(appCtx.getApplicant(), fieldName);
        return Map.of(FeatureConstants.SELF_TARGET_KEY, Collections.singletonMap(fc.getFeatureCode(), value));
    }

    /**
     * 按 StorageLevel 优先级查找依赖特征结果：INSURED → APPLICANT → POLICY → ORDER
     */
    private Object resolveFeatureFromContext(InsuredFeatureContext insCtx, String depFeatureCode) {
        Object val = insCtx.getAcquiredFeatures().get(depFeatureCode);
        if (val != null) return val;

        PolicyFeatureContext polCtx = insCtx.getPolicyContext();
        if (polCtx != null) {
            ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
            if (appCtx != null) {
                val = appCtx.getFeatures().get(depFeatureCode);
                if (val != null) return val;
            }

            val = polCtx.getPolicyFeatures().get(depFeatureCode);
            if (val != null) return val;

            OrderFeatureContext orderCtx = polCtx.getOrderContext();
            if (orderCtx != null) {
                val = orderCtx.getOrderFeatures().get(depFeatureCode);
                if (val != null) return val;
            }
        }

        return null;
    }

    /**
     * 通过反射 + 缓存 Method 读取实体字段值，零硬编码，支持任意嵌套深度。
     * 实体为 null 时返回 null。同时支持 Map 类型的路径导航。
     */
    private Object readFieldValue(Object entity, String path) {
        if (entity == null) return null;

        Object current = entity;
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(segment);
            } else {
                Method getter = getGetter(current.getClass(), segment);
                try {
                    current = getter.invoke(current);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "读取字段失败: " + path + " from " + entity.getClass().getSimpleName(), e);
                }
            }
        }
        return current;
    }

    private Method getGetter(Class<?> clazz, String fieldName) {
        return GETTER_CACHE
                .computeIfAbsent(clazz, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(fieldName, fn -> {
                    String cap = Character.toUpperCase(fn.charAt(0)) + fn.substring(1);
                    try {
                        return clazz.getMethod("get" + cap);
                    } catch (NoSuchMethodException e1) {
                        try {
                            return clazz.getMethod("is" + cap);
                        } catch (NoSuchMethodException e2) {
                            throw new IllegalArgumentException(
                                    clazz.getSimpleName() + " 实体无字段: " + fn);
                        }
                    }
                });
    }
}
