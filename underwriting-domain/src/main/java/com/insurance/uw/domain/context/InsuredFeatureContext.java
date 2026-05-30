package com.insurance.uw.domain.context;

import com.insurance.uw.domain.model.entity.Insured;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 被保人特征上下文 — 持有被保人引用、向上导航、特征结果
 *
 * 零拷贝设计：只持有 Insured 对象引用，不拷贝任何业务字段。
 * 提供代理属性方便 Groovy 脚本直接访问。
 * 双向引用：通过 parentPolicyCtx 向上导航到投保单、订单。
 */
public class InsuredFeatureContext {

    private final Insured insured;
    private final PolicyFeatureContext parentPolicyCtx;
    private final Map<String, Object> acquiredFeatures = new HashMap<>();

    public InsuredFeatureContext(Insured insured, PolicyFeatureContext parentPolicyCtx) {
        this.insured = insured;
        this.parentPolicyCtx = parentPolicyCtx;
    }

    // ---- 原始对象引用 ----
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

    // ---- 双向引用导航 ----
    public PolicyFeatureContext getPolicyContext() { return parentPolicyCtx; }

    public OrderFeatureContext getOrderContext() {
        return parentPolicyCtx != null ? parentPolicyCtx.getOrderContext() : null;
    }

    /**
     * 向上导航获取订单级别的特征值
     */
    public Object getOrderFeature(String key) {
        OrderFeatureContext octx = getOrderContext();
        return octx != null ? octx.getOrderFeatures().get(key) : null;
    }

}
