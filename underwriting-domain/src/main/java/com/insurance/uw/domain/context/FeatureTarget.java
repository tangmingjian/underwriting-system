package com.insurance.uw.domain.context;

/**
 * 精确的特征存储目标：哪个实体、属于哪张保单。
 *
 * <p>解决同人跨保单场景下的歧义：同一个被保人/投保人出现在多张保单中，
 * 但只有部分保单需要某个特征。通过 {@code (policyId, entityId, entityType)}
 * 三元组精确定位目标。</p>
 *
 * <p>由 {@link FeatureTargeting#resolveTargets(String)} 批量生成，
 * 供 {@code FeatureResultDispatcher} 做跨保单过滤。</p>
 *
 * @param policyId   目标保单 ID
 * @param entityId   目标实体 ID（被保人 ID 或投保人 ID）
 * @param entityType 实体类型
 */
public record FeatureTarget(String policyId, String entityId, EntityType entityType) {

    /** 实体类型：被保人或投保人 */
    public enum EntityType {
        INSURED,
        APPLICANT
    }
}
