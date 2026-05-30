package com.insurance.uw.domain.model.entity;

/**
 * 产品实体
 */
public class Product {

    private String productCode;
    private String productName;

    public Product() {}

    public Product(String productCode, String productName) {
        this.productCode = productCode;
        this.productName = productName;
    }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

}
