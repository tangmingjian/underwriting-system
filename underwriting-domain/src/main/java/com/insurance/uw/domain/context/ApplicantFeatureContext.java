package com.insurance.uw.domain.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.uw.domain.model.entity.Applicant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投保人特征上下文 — 持有投保人引用、向上导航、特征结果
 *
 * 零拷贝设计：只持有 Applicant 对象引用，不拷贝任何业务字段。
 * 提供代理属性方便 Groovy 脚本直接访问。
 * 双向引用：通过 parentPolicyCtx 向上导航到投保单、订单。
 */
public class ApplicantFeatureContext {

    @JsonIgnore
    private final Applicant applicant;
    @JsonIgnore
    private final PolicyFeatureContext parentPolicyCtx;
    private final Map<String, Object> features = new ConcurrentHashMap<>();

    public ApplicantFeatureContext(Applicant applicant, PolicyFeatureContext parentPolicyCtx) {
        this.applicant = applicant;
        this.parentPolicyCtx = parentPolicyCtx;
    }

    @JsonIgnore
    public Applicant getApplicant() { return applicant; }

    // ---- 代理属性 ----
    public String getApplicantId() { return applicant.getId(); }
    public String getIdNo() { return applicant.getIdNo(); }
    public String getName() { return applicant.getName(); }
    public int getAge() { return applicant.getAge(); }
    public String getGender() { return applicant.getGender(); }
    /** 同人客户号列表 */
    public List<String> getCustomerNos() { return applicant.getCustomerNos(); }

    public Map<String, Object> getFeatures() { return features; }

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

}
