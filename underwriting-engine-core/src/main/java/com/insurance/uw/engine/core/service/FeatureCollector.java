package com.insurance.uw.engine.core.service;

import com.insurance.uw.engine.core.context.ContextNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用特征收集器 — 沿 ContextNode 父链向上合并所有特征。
 */
public class FeatureCollector {

    /**
     * 为指定 ContextNode 收集其视角下的所有特征。
     * 沿父链向上合并：根节点 → ... → 父 → 当前节点（后者覆盖前者）。
     */
    public static Map<String, Object> collectForNode(ContextNode node) {
        return node.collectFeaturesUpward();
    }
}
