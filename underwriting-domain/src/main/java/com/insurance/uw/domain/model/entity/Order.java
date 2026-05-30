package com.insurance.uw.domain.model.entity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单聚合根
 */
public class Order {

    private String id;
    private String channel;
    private LocalDateTime orderTime;
    private List<Policy> policies;

    public Order() {}

    public Order(String id, String channel, LocalDateTime orderTime, List<Policy> policies) {
        this.id = id;
        this.channel = channel;
        this.orderTime = orderTime;
        this.policies = policies;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public LocalDateTime getOrderTime() { return orderTime; }
    public void setOrderTime(LocalDateTime orderTime) { this.orderTime = orderTime; }

    public List<Policy> getPolicies() { return policies; }
    public void setPolicies(List<Policy> policies) { this.policies = policies; }

}
