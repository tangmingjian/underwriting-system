package com.insurance.uw.engine.core.handler;

import com.insurance.uw.engine.core.constants.FeatureConstants;
import com.insurance.uw.engine.core.context.ContextNode;
import com.insurance.uw.engine.core.enums.CalcType;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.model.valueobject.CalcConfig;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * PARAM_MAPPING 类型处理器：解析 calc_config.source → entityType.fieldName，
 * 通过 ContextNode 树导航 + 反射读取实体字段值。
 *
 * <h3>与旧版的核心区别</h3>
 * <ul>
 *   <li>不再使用 instanceof 分派 4 种上下文类型，统一通过 ContextNode 导航</li>
 *   <li>{@code getEntity()} 替代硬编码 getter（如 ctx.getInsured()）</li>
 *   <li>{@code getParent()} 链替代硬编码 FeatureCollector 查找路径</li>
 * </ul>
 */
public class ParamMappingCalcHandler implements FeatureCalcHandler {

    private static final Logger LOG = Logger.getLogger(ParamMappingCalcHandler.class.getName());
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

        if (!(ctx instanceof ContextNode)) {
            throw new IllegalArgumentException("不支持的上下文类型: " + ctx.getClass().getName());
        }
        ContextNode cn = (ContextNode) ctx;

        Map<String, Object> result = new HashMap<>();

        switch (entityType) {
            case "order":    executeOrderEntity(cn, fc, fieldName, result); break;
            case "policy":   executePolicyEntity(cn, fc, fieldName, result); break;
            case "insured":  executeInsuredEntity(cn, fc, fieldName, result); break;
            case "applicant": executeApplicantEntity(cn, fc, fieldName, result); break;
            case "feature":  executeFeatureDerived(cn, fc, fieldName, result); break;
            default: throw new IllegalArgumentException(
                    "特征 " + fc.getFeatureCode() + " 的 entityType 无效: " + entityType
                            + "，支持: order, policy, insured, applicant, feature");
        }

        return result;
    }

    // ==================== entityType 分派 ====================

    private void executeOrderEntity(ContextNode cn, FeatureConfig fc, String fieldName,
                                     Map<String, Object> result) {
        ContextNode orderNode = cn.findAncestor("ORDER");
        if (orderNode == null) return;

        Object entity = orderNode.getEntity();
        Object value = readFieldValue(entity, fieldName);
        Map<String, Object> featureVal = Collections.singletonMap(fc.getFeatureCode(), value);

        // ORDER 特征写入 order 层级本身
        result.put(FeatureConstants.ORDER_TARGET_KEY, featureVal);

        // 同时写入各子节点的 policyId，支持 Order→Policy/Applicant 路由
        for (ContextNode policyNode : orderNode.getChildren()) {
            result.put(policyNode.getNodeId(), featureVal);
        }
    }

    private void executePolicyEntity(ContextNode cn, FeatureConfig fc, String fieldName,
                                      Map<String, Object> result) {
        ContextNode policyNode = cn.findAncestor("POLICY");
        if (policyNode == null) return;

        Object entity = policyNode.getEntity();
        Object value = readFieldValue(entity, fieldName);
        result.put(policyNode.getNodeId(),
                Collections.singletonMap(fc.getFeatureCode(), value));
    }

    private void executeInsuredEntity(ContextNode cn, FeatureConfig fc, String fieldName,
                                       Map<String, Object> result) {
        ContextNode insuredNode = cn.findAncestor("INSURED");
        if (insuredNode == null) return;

        Object entity = insuredNode.getEntity();
        Object value = readFieldValue(entity, fieldName);
        if (cn.getLevelName().equals("INSURED")) {
            // INSURED 级聚合：使用 SELF_KEY
            result.put(FeatureConstants.SELF_TARGET_KEY,
                    Collections.singletonMap(fc.getFeatureCode(), value));
        } else {
            // ORDER/POLICY 级聚合：使用 insuredId 作为 key
            result.put(insuredNode.getNodeId(),
                    Collections.singletonMap(fc.getFeatureCode(), value));
        }
    }

    private void executeApplicantEntity(ContextNode cn, FeatureConfig fc, String fieldName,
                                         Map<String, Object> result) {
        ContextNode applicantNode = cn.findAncestor("APPLICANT");
        if (applicantNode == null) return;

        Object entity = applicantNode.getEntity();
        Object value = readFieldValue(entity, fieldName);
        if (cn.getLevelName().equals("APPLICANT")) {
            // APPLICANT 级聚合：使用 SELF_KEY
            result.put(FeatureConstants.SELF_TARGET_KEY,
                    Collections.singletonMap(fc.getFeatureCode(), value));
        } else {
            // ORDER/POLICY 级聚合：使用 policyId 作为 key（通过父节点获取）
            ContextNode policyNode = applicantNode.getParent();
            result.put(policyNode != null ? policyNode.getNodeId() : applicantNode.getNodeId(),
                    Collections.singletonMap(fc.getFeatureCode(), value));
        }
    }

    /**
     * feature 类型：从依赖特征的结果中提取子字段。
     * 沿 ContextNode 父链向上查找依赖特征值。
     */
    private void executeFeatureDerived(ContextNode cn, FeatureConfig fc, String fieldName,
                                        Map<String, Object> result) {
        int dot = fieldName.indexOf('.');
        String depFeatureCode = dot > 0 ? fieldName.substring(0, dot) : fieldName;
        String subPath = dot > 0 ? fieldName.substring(dot + 1) : null;

        Object depResult = resolveFeatureFromContext(cn, depFeatureCode);
        Object value = subPath != null ? readFieldValue(depResult, subPath) : depResult;
        result.put(cn.getNodeId(),
                Collections.singletonMap(fc.getFeatureCode(), value));
    }

    /**
     * 沿 ContextNode 父链向上查找依赖特征：当前节点 → 父节点 → ... → 根节点。
     * 替代原来硬编码的 INSURED → APPLICANT → POLICY → ORDER 路径。
     */
    private Object resolveFeatureFromContext(ContextNode node, String depFeatureCode) {
        ContextNode current = node;
        while (current != null) {
            Object val = current.getFeatureStore().get(depFeatureCode);
            if (val != null) return val;
            current = current.getParent();
        }
        return null;
    }

    // ==================== 反射工具 ====================

    /**
     * 通过反射 + 缓存 Method 读取实体字段值，支持嵌套路径和 Map 导航。
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
