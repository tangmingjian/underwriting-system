package com.insurance.uw.engine.core.routing;

import com.insurance.uw.engine.core.context.ContextNode;
import com.insurance.uw.engine.core.enums.AggregationLevel;
import com.insurance.uw.engine.core.enums.StorageLevel;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import com.insurance.uw.engine.core.targeting.FeatureTargeting;

import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 通用特征结果分发器 — 基于 ContextNode 的递归分发。
 *
 * <h3>规则</h3>
 * 结果存储到 StorageLevel 对应的层级，只允许向下或同级存储（不可向上）。
 * 分发算法：
 * <ol>
 *   <li>从 aggNode 出发，比较 aggLevel.depth() 和 storageLevel.depth()</li>
 *   <li>若 storage > agg（向上），拒绝并 warning</li>
 *   <li>若 storage == agg，直接写入 aggNode.getFeatureStore()</li>
 *   <li>若 storage < agg（向下），遍历子节点树找到对应层级节点，写入</li>
 * </ol>
 */
public class FeatureResultDispatcher {

    private static final Logger LOG = Logger.getLogger(FeatureResultDispatcher.class.getName());

    private final ContextNode rootNode;
    private final FeatureTargeting targeting;

    public FeatureResultDispatcher(ContextNode rootNode, FeatureTargeting targeting) {
        this.rootNode = rootNode;
        this.targeting = targeting;
    }

    /**
     * 主入口：将 handler 结果分发到正确的上下文节点。
     *
     * @param aggNode 计算上下文节点（handler 执行所在的 ContextNode）
     * @param fc      特征配置
     * @param results handler 输出：Map&lt;targetKey, featureValue&gt;
     */
    public void dispatch(Object aggNode, FeatureConfig fc, Map<String, Object> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        if (!(aggNode instanceof ContextNode)) {
            LOG.warning("[存储] 不支持的上下文类型: " + aggNode.getClass().getName());
            return;
        }
        ContextNode node = (ContextNode) aggNode;

        AggregationLevel agg = fc.getAggregation();
        StorageLevel storage = fc.getStorageLevel();

        if (storage.depth() > agg.depth()) {
            // 向上存储被拒绝
            LOG.warning("[存储] 拒绝(向上): " + agg + "×" + storage
                    + " " + fc.getFeatureCode() + " nodeId=" + node.getNodeId());
            return;
        }

        if (storage.name().equals(agg.name())) {
            // 同级存储
            dispatchToSelf(node, fc, results);
        } else {
            // 向下存储：找到目标层级节点
            dispatchDownward(node, fc, storage, results);
        }
    }

    /**
     * 同级存储：直接写入当前节点的 featureStore。
     * results 的 key 就是 targetId（或 _self_ / __ORDER__），值直接写入。
     */
    private void dispatchToSelf(ContextNode node, FeatureConfig fc, Map<String, Object> results) {
        for (var entry : results.entrySet()) {
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());
            node.getFeatureStore().putAll(featureMap);
            LOG.info("[存储] " + node.getLevelName() + "×" + node.getLevelName()
                    + ": " + fc.getFeatureCode() + "=" + featureMap
                    + " → nodeId=" + node.getNodeId());
            return; // 同级存储只取第一个 entry（如 _self_ / __ORDER__）
        }
    }

    /**
     * 向下分发：从 aggNode 出发，在子树中找到 storage 层级的节点并写入。
     * results 的 key 是 targetId（如 insuredId / policyId），用于匹配目标节点。
     */
    private void dispatchDownward(ContextNode aggNode, FeatureConfig fc,
                                   StorageLevel storage, Map<String, Object> results) {
        String storageLevelName = storage.name();
        for (var entry : results.entrySet()) {
            String targetId = entry.getKey();
            Map<String, Object> featureMap = unwrapFeatureValue(fc, entry.getValue());

            // 在 aggNode 的子树中查找 targetId + storageLevel 匹配的节点
            boolean found = false;
            for (ContextNode target : aggNode.collectDescendants(storageLevelName)) {
                if (target.getNodeId().equals(targetId)) {
                    target.getFeatureStore().putAll(featureMap);
                    LOG.info("[存储] " + aggNode.getLevelName() + "×" + storageLevelName
                            + ": " + fc.getFeatureCode() + "=" + featureMap
                            + " → nodeId=" + targetId);
                    found = true;
                }
            }
            if (!found) {
                LOG.fine("[存储] " + aggNode.getLevelName() + "×" + storageLevelName
                        + ": " + fc.getFeatureCode() + " targetId=" + targetId + " 未找到匹配节点");
            }
        }
    }

    /**
     * 批量分发
     */
    public void dispatchBatch(Object aggNode, Map<FeatureConfig, Map<String, Object>> batchResults) {
        for (var entry : batchResults.entrySet()) {
            dispatch(aggNode, entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapFeatureValue(FeatureConfig fc, Object value) {
        return (value instanceof Map)
                ? (Map<String, Object>) value
                : Collections.singletonMap(fc.getFeatureCode(), value);
    }
}
