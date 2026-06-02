package com.insurance.uw.feature.core.handler;

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
     * ORDER 级聚合：利用 getInsuredsForFeature / getPoliciesForFeature 只处理相关实体。
     */
    private Map<String, Object> executeOrderLevel(OrderFeatureContext ctx, FeatureConfig fc,
                                                  String entityType, String fieldName) {
        Map<String, Object> result = new HashMap<>();

        switch (entityType) {
            case "order": {
                Object value = readFieldValue(ctx.getOrder(), fieldName);
                Map<String, Object> featureVal = Collections.singletonMap(fc.getFeatureCode(), value);
                result.put("__ORDER__", featureVal);
                // 同时按 policyId 写入，支持 ORDER→POLICY/APPLICANT 的向下存储路由
                for (PolicyFeatureContext polCtx : ctx.getPoliciesForFeature(fc.getFeatureCode())) {
                    result.put(polCtx.getPolicyId(), featureVal);
                }
                break;
            }
            case "policy": {
                for (PolicyFeatureContext polCtx : ctx.getPoliciesForFeature(fc.getFeatureCode())) {
                    Object value = readFieldValue(polCtx.getPolicy(), fieldName);
                    result.put(polCtx.getPolicyId(), Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "insured": {
                for (InsuredFeatureContext insCtx : ctx.getInsuredsForFeature(fc.getFeatureCode())) {
                    Object value = readFieldValue(insCtx.getInsured(), fieldName);
                    result.put(insCtx.getInsuredId(), Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "applicant": {
                for (PolicyFeatureContext polCtx : ctx.getPoliciesForFeature(fc.getFeatureCode())) {
                    ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
                    if (appCtx != null && appCtx.getApplicant() != null) {
                        Object value = readFieldValue(appCtx.getApplicant(), fieldName);
                        result.put(appCtx.getApplicantId(), Collections.singletonMap(fc.getFeatureCode(), value));
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
     * POLICY 级聚合：遍历 polCtx.getInsureds() 全量（因 POLICY 级本身即是保单范围）。
     */
    private Map<String, Object> executePolicyLevel(PolicyFeatureContext polCtx, FeatureConfig fc,
                                                   String entityType, String fieldName) {
        Map<String, Object> result = new HashMap<>();

        switch (entityType) {
            case "order": {
                OrderFeatureContext orderCtx = polCtx.getOrderContext();
                if (orderCtx != null) {
                    Object value = readFieldValue(orderCtx.getOrder(), fieldName);
                    result.put("__ORDER__", Collections.singletonMap(fc.getFeatureCode(), value));
                }
                break;
            }
            case "policy": {
                Object value = readFieldValue(polCtx.getPolicy(), fieldName);
                result.put(polCtx.getPolicyId(), Collections.singletonMap(fc.getFeatureCode(), value));
                break;
            }
            case "insured": {
                for (InsuredFeatureContext insCtx : polCtx.getInsureds()) {
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

                for (InsuredFeatureContext insCtx : polCtx.getInsureds()) {
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
        return Map.of("_self_", Collections.singletonMap(fc.getFeatureCode(), value));
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
        return Map.of("_self_", Collections.singletonMap(fc.getFeatureCode(), value));
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
                    String getterName = "get" + Character.toUpperCase(fn.charAt(0)) + fn.substring(1);
                    try {
                        return clazz.getMethod(getterName);
                    } catch (NoSuchMethodException e) {
                        throw new IllegalArgumentException(
                                clazz.getSimpleName() + " 实体无字段: " + fn);
                    }
                });
    }
}
