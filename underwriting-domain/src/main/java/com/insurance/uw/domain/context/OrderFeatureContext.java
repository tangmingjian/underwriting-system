package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.engine.core.context.ContextNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 订单特征上下文 — 顶层上下文，持有订单引用、所有保单上下文、订单级特征
 *
 * 零拷贝设计：只持有 Order 对象引用。
 * 构建时递归创建所有子级上下文树。
 */
public class OrderFeatureContext implements ContextNode {
    @JsonIgnore
    private final Order order;
    private final List<PolicyFeatureContext> policyContexts;
    private final Map<String, Object> orderFeatures = new ConcurrentHashMap<>();

    /** 保单ID → 保单上下文索引（O(1) 查找） */
    @JsonIgnore
    private final Map<String, PolicyFeatureContext> policyIndex;
    /** 被保人ID → 被保人上下文列表索引（O(1) 查找，同一被保人可出现在多个保单中） */
    @JsonIgnore
    private final Map<String, List<InsuredFeatureContext>> insuredIndex;

    /**
     * 特征-目标路由映射，由调度器在执行前注入（非持久化，仅当前次核保有效）。
     * 封装了输入映射、派生映射及其所有查询逻辑。
     */
    @JsonIgnore
    private FeatureTargeting featureTargeting;

    public OrderFeatureContext(Order order) {
        this.order = order;
        this.policyContexts = order.getPolicies().stream()
                .map(p -> new PolicyFeatureContext(p, this))
                .collect(Collectors.toList());
        this.policyIndex = new HashMap<>();
        this.insuredIndex = new HashMap<>();
        for (PolicyFeatureContext pc : policyContexts) {
            policyIndex.put(pc.getPolicyId(), pc);
            for (InsuredFeatureContext ic : pc.getInsureds()) {
                insuredIndex.computeIfAbsent(ic.getInsuredId(), k -> new ArrayList<>()).add(ic);
            }
        }
    }

    // ---- 原始对象引用（仅内部使用，不参与序列化） ----
    @JsonIgnore
    public Order getOrder() { return order; }

    // ---- 代理属性 ----
    public String getOrderId() { return order.getId(); }
    public String getChannel() { return order.getChannel(); }

    // ---- 子级上下文 ----
    public List<PolicyFeatureContext> getPolicies() { return policyContexts; }

    // ---- 特征结果 ----
    public Map<String, Object> getOrderFeatures() { return orderFeatures; }

    // ---- 特征-目标路由映射 ----

    public void setFeatureTargeting(FeatureTargeting featureTargeting) {
        this.featureTargeting = featureTargeting;
    }

    @JsonIgnore
    public FeatureTargeting getFeatureTargeting() {
        return featureTargeting;
    }

    // ---- 便捷查找 ----

    /**
     * 按被保人ID查找对应的所有被保人上下文（O(1) 索引查找，同一被保人可能出现在多个保单中）
     */
    public List<InsuredFeatureContext> findInsuredCtx(String insuredId) {
        List<InsuredFeatureContext> result = insuredIndex.get(insuredId);
        return result != null ? result : List.of();
    }

    /**
     * 按被保人ID + 特征码查找被保人上下文，只返回保单在 featureInsuredPolicyMap 允许集合中的结果。
     * 若 featureTargeting 为 null 或无该特征的映射，则回退到不过滤的 findInsuredCtx(insuredId)。
     */
    public List<InsuredFeatureContext> findInsuredCtx(String insuredId, String featureCode) {
        var contexts = findInsuredCtx(insuredId);
        if (contexts.isEmpty() || featureTargeting == null) return contexts;
        var allowedPolicies = featureTargeting.getPolicyIdsForInsuredFeature(featureCode, insuredId);
        if (allowedPolicies == null) return contexts;
        if (allowedPolicies.isEmpty()) return List.of();
        return contexts.stream()
                .filter(ic -> allowedPolicies.contains(ic.getPolicyContext().getPolicyId()))
                .collect(Collectors.toList());
    }

    /**
     * 按保单ID查找保单上下文（O(1) 索引查找）
     */
    public PolicyFeatureContext findPolicyCtx(String policyId) {
        return policyIndex.get(policyId);
    }

    /**
     * 按保单ID + 特征码查找保单上下文，若 featurePolicyTargetMap 中该特征不需要此保单则返回 null。
     */
    public PolicyFeatureContext findPolicyCtx(String policyId, String featureCode) {
        if (featureTargeting != null && !featureTargeting.isPolicyTargetedForFeature(policyId, featureCode)) {
            return null;
        }
        return findPolicyCtx(policyId);
    }

    /**
     * 获取所有被保人上下文（扁平化）
     */
    public List<InsuredFeatureContext> getAllInsuredContexts() {
        return policyContexts.stream()
                .flatMap(pc -> pc.getInsureds().stream())
                .collect(Collectors.toList());
    }

    /**
     * 按特征码返回相关被保人上下文。
     *
     * <h3>两级过滤</h3>
     * <ol>
     *   <li><b>被保人维度</b>：通过 {@code featureInsuredTargetMap} 过滤出需要该特征的 insuredId</li>
     *   <li><b>保单维度</b>：通过 {@code featureInsuredPolicyMap} 进一步过滤，同一被保人在不同保单
     *       可能需要不同特征。例如 INS001 在 POL001 需要 [creditScore]，在 POL002 不需要，
     *       则只返回 INS001@POL001，避免对 POL002 下的 INS001Context 做重复计算</li>
     * </ol>
     *
     * <p>优先使用派生映射（含依赖传播），回退到输入映射。若都无匹配 → 回退到 getAllInsuredContexts()。</p>
     */
    public List<InsuredFeatureContext> getInsuredsForFeature(String featureCode) {
        Set<String> matchingIds = featureTargeting != null
                ? featureTargeting.getInsuredIdsForFeature(featureCode) : null;

        if (matchingIds == null || matchingIds.isEmpty()) {
            return getAllInsuredContexts();
        }
        return policyContexts.stream()
                .flatMap(pc -> pc.getInsureds().stream())
                .filter(ic -> {
                    // 第一级：被保人ID 匹配
                    if (!matchingIds.contains(ic.getInsuredId())) return false;
                    // 第二级：保单维度精确匹配（同一被保人在不同保单可能需要不同特征）
                    if (featureTargeting != null) {
                        Set<String> allowed = featureTargeting.getPolicyIdsForInsuredFeature(
                                featureCode, ic.getInsuredId());
                        // allowed=null  → 无映射信息，不过滤
                        // allowed=empty → 该被保人不需要此特征，过滤掉
                        // allowed=非空  → 只保留在这些保单中的上下文
                        if (allowed != null) {
                            return allowed.contains(ic.getPolicyContext().getPolicyId());
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    // ==================== ContextNode 接口实现 ====================

    @Override
    public String getNodeId() { return getOrderId(); }

    @Override
    public String getLevelName() { return "ORDER"; }

    @Override
    public ContextNode getParent() { return null; }

    @Override
    public List<? extends ContextNode> getChildren() { return policyContexts; }

    @Override
    public Map<String, Object> getFeatureStore() { return orderFeatures; }

    @Override
    public Object getEntity() { return order; }

    /**
     * 按特征码返回相关保单上下文（用于收集对应投保人）。
     * 优先使用派生映射（含依赖传播），回退到输入映射。
     * 若都无匹配 → 回退到 getPolicies()。
     */
    public List<PolicyFeatureContext> getPoliciesForFeature(String featureCode) {
        Set<String> matchingPolicyIds = featureTargeting != null
                ? featureTargeting.getPolicyIdsForFeature(featureCode) : null;

        if (matchingPolicyIds == null || matchingPolicyIds.isEmpty()) {
            return getPolicies();
        }
        return policyContexts.stream()
                .filter(pc -> matchingPolicyIds.contains(pc.getPolicyId()))
                .collect(Collectors.toList());
    }

}
