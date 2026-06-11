package com.insurance.uw.bootstrap.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 领域模型 ↔ 引擎模型字段拷贝适配器。
 *
 * <p>engine-core 和 domain 有同名但不同包的 FeatureConfig、FeatureScript 等类，
 * 此适配器逐字段复制，解决类型冲突。</p>
 */
public class ModelAdapter {

    private ModelAdapter() {}

    // ==================== FeatureConfig ====================

    /**
     * 引擎 FeatureConfig → 领域 FeatureConfig
     */
    public static com.insurance.uw.domain.model.entity.FeatureConfig toDomain(
            com.insurance.uw.engine.core.model.entity.FeatureConfig eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.entity.FeatureConfig domain =
                new com.insurance.uw.domain.model.entity.FeatureConfig();
        domain.setId(eng.getId());
        domain.setFeatureCode(eng.getFeatureCode());
        domain.setFeatureName(eng.getFeatureName());
        if (eng.getCategory() != null) {
            domain.setCategory(com.insurance.uw.common.enums.FeatureCategory.valueOf(eng.getCategory().name()));
        }
        if (eng.getDataType() != null) {
            domain.setDataType(com.insurance.uw.common.enums.DataType.valueOf(eng.getDataType().name()));
        }
        if (eng.getCalcType() != null) {
            domain.setCalcType(com.insurance.uw.common.enums.CalcType.valueOf(eng.getCalcType().name()));
        }
        domain.setCalcConfigJson(eng.getCalcConfigJson());
        if (eng.getAggregation() != null) {
            domain.setAggregation(com.insurance.uw.common.enums.AggregationLevel.valueOf(eng.getAggregation().name()));
        }
        if (eng.getStorageLevel() != null) {
            domain.setStorageLevel(com.insurance.uw.common.enums.StorageLevel.valueOf(eng.getStorageLevel().name()));
        }
        domain.setVersion(eng.getVersion());
        domain.setDefaultValue(eng.getDefaultValue());
        domain.setTtlSeconds(eng.getTtlSeconds());
        domain.setSourceSystem(eng.getSourceSystem());
        domain.setOwner(eng.getOwner());
        if (eng.getStatus() != null) {
            domain.setStatus(com.insurance.uw.common.enums.FeatureStatus.valueOf(eng.getStatus().name()));
        }
        domain.setExtraParams(eng.getExtraParams());
        domain.setDependsOn(eng.getDependsOn() != null ? new ArrayList<>(eng.getDependsOn()) : null);
        domain.setCreateTime(eng.getCreateTime());
        domain.setUpdateTime(eng.getUpdateTime());
        return domain;
    }

    /**
     * 领域 FeatureConfig → 引擎 FeatureConfig
     */
    public static com.insurance.uw.engine.core.model.entity.FeatureConfig toEngine(
            com.insurance.uw.domain.model.entity.FeatureConfig domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.entity.FeatureConfig eng =
                new com.insurance.uw.engine.core.model.entity.FeatureConfig();
        eng.setId(domain.getId());
        eng.setFeatureCode(domain.getFeatureCode());
        eng.setFeatureName(domain.getFeatureName());
        if (domain.getCategory() != null) {
            eng.setCategory(com.insurance.uw.engine.core.enums.FeatureCategory.valueOf(domain.getCategory().name()));
        }
        if (domain.getDataType() != null) {
            eng.setDataType(com.insurance.uw.engine.core.enums.DataType.valueOf(domain.getDataType().name()));
        }
        if (domain.getCalcType() != null) {
            eng.setCalcType(com.insurance.uw.engine.core.enums.CalcType.valueOf(domain.getCalcType().name()));
        }
        eng.setCalcConfigJson(domain.getCalcConfigJson());
        if (domain.getAggregation() != null) {
            eng.setAggregation(com.insurance.uw.engine.core.enums.AggregationLevel.valueOf(domain.getAggregation().name()));
        }
        if (domain.getStorageLevel() != null) {
            eng.setStorageLevel(com.insurance.uw.engine.core.enums.StorageLevel.valueOf(domain.getStorageLevel().name()));
        }
        eng.setVersion(domain.getVersion());
        eng.setDefaultValue(domain.getDefaultValue());
        eng.setTtlSeconds(domain.getTtlSeconds());
        eng.setSourceSystem(domain.getSourceSystem());
        eng.setOwner(domain.getOwner());
        if (domain.getStatus() != null) {
            eng.setStatus(com.insurance.uw.engine.core.enums.FeatureStatus.valueOf(domain.getStatus().name()));
        }
        eng.setExtraParams(domain.getExtraParams());
        eng.setDependsOn(domain.getDependsOn() != null ? new ArrayList<>(domain.getDependsOn()) : null);
        eng.setCreateTime(domain.getCreateTime());
        eng.setUpdateTime(domain.getUpdateTime());
        return eng;
    }

    // ==================== FeatureScript ====================

    /**
     * 引擎 FeatureScript → 领域 FeatureScript
     */
    public static com.insurance.uw.domain.model.entity.FeatureScript toDomain(
            com.insurance.uw.engine.core.model.entity.FeatureScript eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.entity.FeatureScript domain =
                new com.insurance.uw.domain.model.entity.FeatureScript();
        domain.setId(eng.getId());
        domain.setScriptId(eng.getScriptId());
        domain.setScriptName(eng.getScriptName());
        if (eng.getScriptType() != null) {
            domain.setScriptType(
                    com.insurance.uw.domain.model.entity.FeatureScript.ScriptType.valueOf(eng.getScriptType().name()));
        }
        domain.setScriptText(eng.getScriptText());
        domain.setVersion(eng.getVersion());
        if (eng.getStatus() != null) {
            domain.setStatus(com.insurance.uw.common.enums.FeatureStatus.valueOf(eng.getStatus().name()));
        }
        domain.setDescription(eng.getDescription());
        domain.setCreateTime(eng.getCreateTime());
        domain.setUpdateTime(eng.getUpdateTime());
        return domain;
    }

    /**
     * 领域 FeatureScript → 引擎 FeatureScript
     */
    public static com.insurance.uw.engine.core.model.entity.FeatureScript toEngine(
            com.insurance.uw.domain.model.entity.FeatureScript domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.entity.FeatureScript eng =
                new com.insurance.uw.engine.core.model.entity.FeatureScript();
        eng.setId(domain.getId());
        eng.setScriptId(domain.getScriptId());
        eng.setScriptName(domain.getScriptName());
        if (domain.getScriptType() != null) {
            eng.setScriptType(
                    com.insurance.uw.engine.core.model.entity.FeatureScript.ScriptType.valueOf(domain.getScriptType().name()));
        }
        eng.setScriptText(domain.getScriptText());
        eng.setVersion(domain.getVersion());
        if (domain.getStatus() != null) {
            eng.setStatus(com.insurance.uw.engine.core.enums.FeatureStatus.valueOf(domain.getStatus().name()));
        }
        eng.setDescription(domain.getDescription());
        eng.setCreateTime(domain.getCreateTime());
        eng.setUpdateTime(domain.getUpdateTime());
        return eng;
    }

    // ==================== ServiceConfig ====================

    public static com.insurance.uw.domain.model.valueobject.ServiceConfig toDomain(
            com.insurance.uw.engine.core.model.valueobject.ServiceConfig eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.valueobject.ServiceConfig domain =
                new com.insurance.uw.domain.model.valueobject.ServiceConfig();
        domain.setDiscoveryType(eng.getDiscoveryType());
        domain.setServiceName(eng.getServiceName());
        domain.setNamespace(eng.getNamespace());
        domain.setGroup(eng.getGroup());
        domain.setStaticEndpoints(eng.getStaticEndpoints() != null
                ? new ArrayList<>(eng.getStaticEndpoints()) : null);
        domain.setProtocol(eng.getProtocol());
        domain.setPath(eng.getPath());
        domain.setMethod(eng.getMethod());
        domain.setTimeoutMs(eng.getTimeoutMs());
        domain.setHeaders(eng.getHeaders() != null ? new LinkedHashMap<>(eng.getHeaders()) : null);
        return domain;
    }

    public static com.insurance.uw.engine.core.model.valueobject.ServiceConfig toEngine(
            com.insurance.uw.domain.model.valueobject.ServiceConfig domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.valueobject.ServiceConfig eng =
                new com.insurance.uw.engine.core.model.valueobject.ServiceConfig();
        eng.setDiscoveryType(domain.getDiscoveryType());
        eng.setServiceName(domain.getServiceName());
        eng.setNamespace(domain.getNamespace());
        eng.setGroup(domain.getGroup());
        eng.setStaticEndpoints(domain.getStaticEndpoints() != null
                ? new ArrayList<>(domain.getStaticEndpoints()) : null);
        eng.setProtocol(domain.getProtocol());
        eng.setPath(domain.getPath());
        eng.setMethod(domain.getMethod());
        eng.setTimeoutMs(domain.getTimeoutMs());
        eng.setHeaders(domain.getHeaders() != null ? new LinkedHashMap<>(domain.getHeaders()) : null);
        return eng;
    }

    // ==================== CrossDecisionTable ====================

    public static com.insurance.uw.domain.model.entity.CrossDecisionTable toDomain(
            com.insurance.uw.engine.core.model.entity.CrossDecisionTable eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.entity.CrossDecisionTable domain =
                new com.insurance.uw.domain.model.entity.CrossDecisionTable();
        domain.setId(eng.getId());
        domain.setTableCode(eng.getTableCode());
        domain.setTableName(eng.getTableName());
        domain.setRowFeature(eng.getRowFeature());
        domain.setColFeature(eng.getColFeature());
        domain.setCells(eng.getCells());
        domain.setDefaultResult(eng.getDefaultResult());
        domain.setStatus(eng.getStatus());
        domain.setCreateTime(eng.getCreateTime());
        domain.setUpdateTime(eng.getUpdateTime());
        return domain;
    }

    public static com.insurance.uw.engine.core.model.entity.CrossDecisionTable toEngine(
            com.insurance.uw.domain.model.entity.CrossDecisionTable domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.entity.CrossDecisionTable eng =
                new com.insurance.uw.engine.core.model.entity.CrossDecisionTable();
        eng.setId(domain.getId());
        eng.setTableCode(domain.getTableCode());
        eng.setTableName(domain.getTableName());
        eng.setRowFeature(domain.getRowFeature());
        eng.setColFeature(domain.getColFeature());
        eng.setCells(domain.getCells());
        eng.setDefaultResult(domain.getDefaultResult());
        eng.setStatus(domain.getStatus());
        eng.setCreateTime(domain.getCreateTime());
        eng.setUpdateTime(domain.getUpdateTime());
        return eng;
    }

    // ==================== ScorecardConfig ====================

    public static com.insurance.uw.domain.model.entity.ScorecardConfig toDomain(
            com.insurance.uw.engine.core.model.entity.ScorecardConfig eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.entity.ScorecardConfig domain =
                new com.insurance.uw.domain.model.entity.ScorecardConfig();
        domain.setId(eng.getId());
        domain.setScorecardCode(eng.getScorecardCode());
        domain.setScorecardName(eng.getScorecardName());
        domain.setDimensions(eng.getDimensions());
        domain.setScoringFormula(eng.getScoringFormula());
        domain.setBuckets(eng.getBuckets());
        domain.setStatus(eng.getStatus());
        domain.setCreateTime(eng.getCreateTime());
        domain.setUpdateTime(eng.getUpdateTime());
        return domain;
    }

    public static com.insurance.uw.engine.core.model.entity.ScorecardConfig toEngine(
            com.insurance.uw.domain.model.entity.ScorecardConfig domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.entity.ScorecardConfig eng =
                new com.insurance.uw.engine.core.model.entity.ScorecardConfig();
        eng.setId(domain.getId());
        eng.setScorecardCode(domain.getScorecardCode());
        eng.setScorecardName(domain.getScorecardName());
        eng.setDimensions(domain.getDimensions());
        eng.setScoringFormula(domain.getScoringFormula());
        eng.setBuckets(domain.getBuckets());
        eng.setStatus(domain.getStatus());
        eng.setCreateTime(domain.getCreateTime());
        eng.setUpdateTime(domain.getUpdateTime());
        return eng;
    }

    // ==================== UnderwritingRule ====================

    public static com.insurance.uw.domain.model.entity.UnderwritingRule toDomain(
            com.insurance.uw.engine.core.model.entity.UnderwritingRule eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.entity.UnderwritingRule domain =
                new com.insurance.uw.domain.model.entity.UnderwritingRule();
        domain.setId(eng.getId());
        domain.setRuleCode(eng.getRuleCode());
        domain.setRuleName(eng.getRuleName());
        if (eng.getRuleType() != null) {
            domain.setRuleType(com.insurance.uw.common.enums.RuleType.valueOf(eng.getRuleType().name()));
        }
        domain.setExpression(eng.getExpression());
        if (eng.getEvalType() != null) {
            domain.setEvalType(com.insurance.uw.common.enums.EvalType.valueOf(eng.getEvalType().name()));
        }
        domain.setFeatureCodes(eng.getFeatureCodes());
        domain.setProductCode(eng.getProductCode());
        domain.setPriority(eng.getPriority());
        domain.setStatus(eng.getStatus());
        domain.setVersion(eng.getVersion());
        domain.setCreateTime(eng.getCreateTime());
        domain.setUpdateTime(eng.getUpdateTime());
        domain.setWordingConfig(eng.getWordingConfig());
        return domain;
    }

    public static com.insurance.uw.engine.core.model.entity.UnderwritingRule toEngine(
            com.insurance.uw.domain.model.entity.UnderwritingRule domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.entity.UnderwritingRule eng =
                new com.insurance.uw.engine.core.model.entity.UnderwritingRule();
        eng.setId(domain.getId());
        eng.setRuleCode(domain.getRuleCode());
        eng.setRuleName(domain.getRuleName());
        if (domain.getRuleType() != null) {
            eng.setRuleType(com.insurance.uw.engine.core.enums.RuleType.valueOf(domain.getRuleType().name()));
        }
        eng.setExpression(domain.getExpression());
        if (domain.getEvalType() != null) {
            eng.setEvalType(com.insurance.uw.engine.core.enums.EvalType.valueOf(domain.getEvalType().name()));
        }
        eng.setFeatureCodes(domain.getFeatureCodes());
        eng.setProductCode(domain.getProductCode());
        eng.setPriority(domain.getPriority());
        eng.setStatus(domain.getStatus());
        eng.setVersion(domain.getVersion());
        eng.setCreateTime(domain.getCreateTime());
        eng.setUpdateTime(domain.getUpdateTime());
        eng.setWordingConfig(domain.getWordingConfig());
        return eng;
    }

    // ==================== UnderwritingRuleHistory ====================

    public static com.insurance.uw.domain.model.entity.UnderwritingRuleHistory toDomain(
            com.insurance.uw.engine.core.model.entity.UnderwritingRuleHistory eng) {
        if (eng == null) return null;
        com.insurance.uw.domain.model.entity.UnderwritingRuleHistory domain =
                new com.insurance.uw.domain.model.entity.UnderwritingRuleHistory();
        domain.setId(eng.getId());
        domain.setRuleCode(eng.getRuleCode());
        domain.setRuleName(eng.getRuleName());
        if (eng.getRuleType() != null) {
            domain.setRuleType(com.insurance.uw.common.enums.RuleType.valueOf(eng.getRuleType().name()));
        }
        domain.setExpression(eng.getExpression());
        if (eng.getEvalType() != null) {
            domain.setEvalType(com.insurance.uw.common.enums.EvalType.valueOf(eng.getEvalType().name()));
        }
        domain.setFeatureCodes(eng.getFeatureCodes());
        domain.setProductCode(eng.getProductCode());
        domain.setPriority(eng.getPriority());
        domain.setStatus(eng.getStatus());
        domain.setVersion(eng.getVersion());
        domain.setCreateTime(eng.getCreateTime());
        domain.setWordingConfig(eng.getWordingConfig());
        return domain;
    }

    public static com.insurance.uw.engine.core.model.entity.UnderwritingRuleHistory toEngine(
            com.insurance.uw.domain.model.entity.UnderwritingRuleHistory domain) {
        if (domain == null) return null;
        com.insurance.uw.engine.core.model.entity.UnderwritingRuleHistory eng =
                new com.insurance.uw.engine.core.model.entity.UnderwritingRuleHistory();
        eng.setId(domain.getId());
        eng.setRuleCode(domain.getRuleCode());
        eng.setRuleName(domain.getRuleName());
        if (domain.getRuleType() != null) {
            eng.setRuleType(com.insurance.uw.engine.core.enums.RuleType.valueOf(domain.getRuleType().name()));
        }
        eng.setExpression(domain.getExpression());
        if (domain.getEvalType() != null) {
            eng.setEvalType(com.insurance.uw.engine.core.enums.EvalType.valueOf(domain.getEvalType().name()));
        }
        eng.setFeatureCodes(domain.getFeatureCodes());
        eng.setProductCode(domain.getProductCode());
        eng.setPriority(domain.getPriority());
        eng.setStatus(domain.getStatus());
        eng.setVersion(domain.getVersion());
        eng.setCreateTime(domain.getCreateTime());
        eng.setWordingConfig(domain.getWordingConfig());
        return eng;
    }
}
