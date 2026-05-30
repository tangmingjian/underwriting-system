package com.insurance.uw.domain.context;

import com.insurance.uw.domain.model.entity.Applicant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投保人特征上下文 — 持有投保人引用及特征结果
 */
public class ApplicantFeatureContext {

    private final Applicant applicant;
    private final Map<String, Object> features = new HashMap<>();

    public ApplicantFeatureContext(Applicant applicant) {
        this.applicant = applicant;
    }

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

}
