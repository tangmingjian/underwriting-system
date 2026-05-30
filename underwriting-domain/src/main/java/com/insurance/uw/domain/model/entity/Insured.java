package com.insurance.uw.domain.model.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 被保人实体
 */
public class Insured {

    private String id;
    private String idNo;
    private String name;
    private int age;
    private String gender;
    private String occupation;
    private String phone;
    /** 同人客户号列表（同一自然人在多个系统中的客户号） */
    private List<String> customerNos = new ArrayList<>();

    public Insured() {}

    public Insured(String id, String name, int age, String gender) {
        this.id = id;
        this.name = name;
        this.age = age;
        this.gender = gender;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIdNo() { return idNo; }
    public void setIdNo(String idNo) { this.idNo = idNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getOccupation() { return occupation; }
    public void setOccupation(String occupation) { this.occupation = occupation; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public List<String> getCustomerNos() { return customerNos; }
    public void setCustomerNos(List<String> customerNos) { this.customerNos = customerNos; }

}
