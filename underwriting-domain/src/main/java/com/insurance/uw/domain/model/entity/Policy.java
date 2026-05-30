package com.insurance.uw.domain.model.entity;

import java.util.List;

/**
 * 投保单实体
 */
public class Policy {

    private String id;
    private Product product;
    private Applicant applicant;
    private List<Insured> insureds;
    private double declaredIncome;
    private double appliedAmount;

    public Policy() {}

    public Policy(String id, Product product, Applicant applicant, List<Insured> insureds) {
        this.id = id;
        this.product = product;
        this.applicant = applicant;
        this.insureds = insureds;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public Applicant getApplicant() { return applicant; }
    public void setApplicant(Applicant applicant) { this.applicant = applicant; }

    public List<Insured> getInsureds() { return insureds; }
    public void setInsureds(List<Insured> insureds) { this.insureds = insureds; }

    public double getDeclaredIncome() { return declaredIncome; }
    public void setDeclaredIncome(double declaredIncome) { this.declaredIncome = declaredIncome; }

    public double getAppliedAmount() { return appliedAmount; }
    public void setAppliedAmount(double appliedAmount) { this.appliedAmount = appliedAmount; }

}
