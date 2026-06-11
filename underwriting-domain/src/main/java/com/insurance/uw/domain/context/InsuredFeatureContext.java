package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.uw.domain.model.entity.Insured;
import com.insurance.uw.engine.core.context.ContextNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被保人特征上下文 — 持有被保人引用、向上导航、特征结果
 *
 * 零拷贝设计：只持有 Insured 对象引用，不拷贝任何业务字段。
 * 提供代理属性方便 Groovy 脚本直接访问。
 * 双向引用：通过 parentPolicyCtx 向上导航到投保单、订单。
 */
public class InsuredFeatureContext implements ContextNode {
    @JsonIgnore
    private final Insured insured;
    @JsonIgnore
    private final PolicyFeatureContext parentPolicyCtx;
    private final Map<String, Object> acquiredFeatures = new ConcurrentHashMap<>();

    public InsuredFeatureContext(Insured insured, PolicyFeatureContext parentPolicyCtx) {
        this.insured = insured;
        this.parentPolicyCtx = parentPolicyCtx;
    }

    // ---- 原始对象引用（仅内部使用，不参与序列化） ----
    @JsonIgnore
    public Insured getInsured() { return insured; }

    // ---- 代理属性，方便 Groovy 脚本直接访问 ----
    public String getInsuredId() { return insured.getId(); }
    public String getIdNo() { return insured.getIdNo(); }
    public String getName() { return insured.getName(); }
    public int getAge() { return insured.getAge(); }
    public String getGender() { return insured.getGender(); }
    public String getOccupation() { return insured.getOccupation(); }
    public String getPhone() { return insured.getPhone(); }
    /** 同人客户号列表 */
    public List<String> getCustomerNos() { return insured.getCustomerNos(); }

    // ---- 特征结果 ----
    public Map<String, Object> getAcquiredFeatures() { return acquiredFeatures; }

    // ---- 双向引用导航（仅内部导航，不参与序列化） ----
    @JsonIgnore
    public PolicyFeatureContext getPolicyContext() { return parentPolicyCtx; }

    @JsonIgnore
    public OrderFeatureContext getOrderContext() {
        return parentPolicyCtx != null ? parentPolicyCtx.getOrderContext() : null;
    }

    /**
     * 向上导航获取订单级别的特征值（仅内部使用，不参与序列化）
     */
    @JsonIgnore
    public Object getOrderFeature(String key) {
        OrderFeatureContext octx = getOrderContext();
        return octx != null ? octx.getOrderFeatures().get(key) : null;
    }

    // ---- ContextNode 接口实现 ----

    @Override
    public String getNodeId() { return getInsuredId(); }

    @Override
    public String getLevelName() { return "INSURED"; }

    @Override
    public ContextNode getParent() { return parentPolicyCtx; }

    @Override
    public List<? extends ContextNode> getChildren() { return List.of(); }

    @Override
    public Map<String, Object> getFeatureStore() { return acquiredFeatures; }

    @Override
    public Object getEntity() { return insured; }

}
