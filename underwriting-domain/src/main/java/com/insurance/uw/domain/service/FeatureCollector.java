package com.insurance.uw.domain.service;

import com.insurance.uw.domain.context.ApplicantFeatureContext;
import com.insurance.uw.domain.context.InsuredFeatureContext;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.context.PolicyFeatureContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 特征收集器 — 从上下文树扁平化收集所有特征，供规则引擎使用
 *
 * 收集顺序：订单特征 → 保单特征 → 投保人特征 → 被保人自身特征（后放入的可覆盖同名）
 */
public class FeatureCollector {

    /**
     * 收集被保人维度的所有特征（包含订单、保单、投保人特征）
     */
    public static Map<String, Object> collectForInsured(InsuredFeatureContext insCtx) {
        Map<String, Object> all = new LinkedHashMap<>();

        if (insCtx.getOrderContext() != null) {
            all.putAll(insCtx.getOrderContext().getOrderFeatures());
        }

        PolicyFeatureContext polCtx = insCtx.getPolicyContext();
        if (polCtx != null) {
            all.putAll(polCtx.getPolicyFeatures());

            ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
            if (appCtx != null) {
                all.putAll(appCtx.getFeatures());
            }
        }

        all.putAll(insCtx.getAcquiredFeatures());

        return all;
    }

    /**
     * 收集投保人维度的所有特征（包含订单、保单特征）
     */
    public static Map<String, Object> collectForApplicant(PolicyFeatureContext polCtx) {
        Map<String, Object> all = new LinkedHashMap<>();

        if (polCtx.getOrderContext() != null) {
            all.putAll(polCtx.getOrderContext().getOrderFeatures());
        }

        all.putAll(polCtx.getPolicyFeatures());

        ApplicantFeatureContext appCtx = polCtx.getApplicantCtx();
        if (appCtx != null) {
            all.putAll(appCtx.getFeatures());
        }

        return all;
    }

    /**
     * 收集保单维度的所有特征（包含订单特征）
     */
    public static Map<String, Object> collectForPolicy(PolicyFeatureContext polCtx) {
        Map<String, Object> all = new LinkedHashMap<>();

        if (polCtx.getOrderContext() != null) {
            all.putAll(polCtx.getOrderContext().getOrderFeatures());
        }

        all.putAll(polCtx.getPolicyFeatures());

        return all;
    }

    /**
     * 收集订单维度的所有特征（仅有订单级特征）
     */
    public static Map<String, Object> collectForOrder(OrderFeatureContext orderCtx) {
        return new LinkedHashMap<>(orderCtx.getOrderFeatures());
    }

}
