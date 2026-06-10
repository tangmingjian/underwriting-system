package com.insurance.uw.bootstrap;

import com.insurance.uw.domain.service.FeatureResultCache;
import com.insurance.uw.domain.service.GroovyMappingEngine;
import com.insurance.uw.infrastructure.cache.CacheOps;

/**
 * 缓存管理服务 — 提供一键清理所有缓存（Redis + 本地 Caffeine）的能力。
 *
 * <p>覆盖范围：FeatureConfig / FeatureScript / UnderwritingRule / ScorecardConfig /
 * CrossDecisionTable / FeatureResult / Groovy 编译类缓存</p>
 */
public class CacheManagementService {

    private final CacheOps cacheOps;
    private final GroovyMappingEngine groovyEngine;
    private final FeatureResultCache featureResultCache;

    public CacheManagementService(CacheOps cacheOps,
                                  GroovyMappingEngine groovyEngine,
                                  FeatureResultCache featureResultCache) {
        this.cacheOps = cacheOps;
        this.groovyEngine = groovyEngine;
        this.featureResultCache = featureResultCache;
    }

    /**
     * 清除所有缓存。
     *
     * @return 各前缀清理的 key 数量汇总
     */
    public String clearAll() {
        StringBuilder sb = new StringBuilder();

        long fc = cacheOps.deleteByPrefix(CacheOps.PREFIX_FC);
        sb.append("fc:").append(fc).append(" ");

        long script = cacheOps.deleteByPrefix(CacheOps.PREFIX_SCRIPT);
        sb.append("script:").append(script).append(" ");

        long rule = cacheOps.deleteByPrefix(CacheOps.PREFIX_RULE);
        sb.append("rule:").append(rule).append(" ");

        long ruleHist = cacheOps.deleteByPrefix(CacheOps.PREFIX_RULE_HISTORY);
        sb.append("ruleHistory:").append(ruleHist).append(" ");

        long sc = cacheOps.deleteByPrefix(CacheOps.PREFIX_SC);
        sb.append("sc:").append(sc).append(" ");

        long cdt = cacheOps.deleteByPrefix(CacheOps.PREFIX_CDT);
        sb.append("cdt:").append(cdt).append(" ");

        featureResultCache.evictAll();
        sb.append("result:cleared ");

        groovyEngine.evictAll();
        sb.append("groovy:cleared");

        return sb.toString().trim();
    }
}
