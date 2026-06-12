package com.insurance.uw.engine.core.context;

import java.util.List;
import java.util.Map;

/**
 * 通用上下文节点接口 — 核保、保全等系统的上下文树节点需实现此接口。
 *
 * <p>通过此接口，引擎核心组件（Handler / Dispatcher / Engine）
 * 无需关心具体的领域实体类型，只需操作抽象的树结构。</p>
 *
 * <h3>树导航约定</h3>
 * <ul>
 *   <li>{@link #getNodeId()} 返回当前节点的业务 ID（如 insuredId、policyId）</li>
 *   <li>{@link #getLevelName()} 返回层级名称，需与 AggregationLevel / StorageLevel 的 name() 一致</li>
 *   <li>{@link #getParent()} 向上导航，根节点返回 null</li>
 *   <li>{@link #getChildren()} 返回子节点列表，叶子节点返回空列表</li>
 *   <li>{@link #getFeatureStore()} 返回当前节点的特征存储 Map（读写）</li>
 *   <li>{@link #getEntity()} 返回领域实体，供 ParamMapping 等通过反射读取字段</li>
 * </ul>
 */
public interface ContextNode {

    /** 当前节点的业务 ID */
    String getNodeId();

    /** 层级名称，需与 AggregationLevel / StorageLevel 的 name() 一致 */
    String getLevelName();

    /** 父节点，根节点返回 null */
    ContextNode getParent();

    /** 子节点列表，叶子节点返回空列表 */
    List<? extends ContextNode> getChildren();

    /** 当前节点的特征存储（可变 Map，引擎写入特征结果） */
    Map<String, Object> getFeatureStore();

    /** 领域实体对象，供反射取值 */
    Object getEntity();

    /**
     * 沿父链向上查找，返回第一个 levelName 匹配的祖先节点（含自身）。
     * 若无匹配则返回 null。
     */
    default ContextNode findAncestor(String levelName) {
        ContextNode current = this;
        while (current != null) {
            if (levelName.equals(current.getLevelName())) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 深度优先遍历收集所有后代节点中指定 levelName 的节点。
     */
    @SuppressWarnings("unchecked")
    default <T extends ContextNode> List<T> collectDescendants(String levelName) {
        List<T> result = new java.util.ArrayList<>();
        collectDescendants(this, levelName, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends ContextNode> void collectDescendants(
            ContextNode node, String levelName, List<T> result) {
        for (ContextNode child : node.getChildren()) {
            if (levelName.equals(child.getLevelName())) {
                result.add((T) child);
            }
            collectDescendants(child, levelName, result);
        }
    }

    /**
     * 沿父链向上合并所有祖先节点的特征（离当前节点越近的优先级越高）。
     * 顺序：最远祖先 → ... → 父节点 → 当前节点（后者覆盖前者）。
     */
    default Map<String, Object> collectFeaturesUpward() {
        java.util.LinkedHashMap<String, Object> all = new java.util.LinkedHashMap<>();
        collectFeaturesUpward(this, all);
        return all;
    }

    private static void collectFeaturesUpward(ContextNode node,
                                              java.util.LinkedHashMap<String, Object> all) {
        if (node == null) return;
        collectFeaturesUpward(node.getParent(), all);
        all.putAll(node.getFeatureStore());
    }
}
