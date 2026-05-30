package com.insurance.uw.domain.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.insurance.uw.common.enums.*;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.persistence.JsonListTypeHandler;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 特征配置聚合根 —— 映射 t_feature_config 表
 */
@TableName(value = "t_feature_config", autoResultMap = true)
public class FeatureConfig {

    private Long id;
    private String featureCode;
    private String featureName;
    private FeatureCategory category;
    private DataType dataType;
    private CalcType calcType;

    /** calc_config JSON 原文（数据库映射），应用层通过 getCalcConfig() 获取解析后的对象 */
    @TableField("calc_config")
    private String calcConfigJson;

    private AggregationLevel aggregation;
    private StorageLevel storageLevel;
    private Integer version;
    private String defaultValue;
    private Integer ttlSeconds;
    private String sourceSystem;
    private String owner;
    private FeatureStatus status;
    private String extraParams;

    @TableField(value = "depends_on", typeHandler = JsonListTypeHandler.class)
    private List<String> dependsOn;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public FeatureConfig() {}

    // ==================== getters / setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFeatureCode() { return featureCode; }
    public void setFeatureCode(String featureCode) { this.featureCode = featureCode; }

    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }

    public FeatureCategory getCategory() { return category; }
    public void setCategory(FeatureCategory category) { this.category = category; }

    public DataType getDataType() { return dataType; }
    public void setDataType(DataType dataType) { this.dataType = dataType; }

    public CalcType getCalcType() { return calcType; }
    public void setCalcType(CalcType calcType) { this.calcType = calcType; }

    /**
     * 获取 calc_config JSON 原始字符串
     */
    public String getCalcConfigJson() { return calcConfigJson; }
    public void setCalcConfigJson(String calcConfigJson) { this.calcConfigJson = calcConfigJson; }

    /**
     * 将 calc_config JSON 解析为强类型对象
     */
    public CalcConfig getCalcConfig() {
        return CalcConfig.fromJson(this.calcConfigJson);
    }

    /**
     * 将强类型对象序列化为 calc_config JSON
     */
    public void setCalcConfig(CalcConfig calcConfig) {
        this.calcConfigJson = calcConfig != null ? calcConfig.toJson() : null;
    }

    public AggregationLevel getAggregation() { return aggregation; }
    public void setAggregation(AggregationLevel aggregation) { this.aggregation = aggregation; }

    public StorageLevel getStorageLevel() { return storageLevel; }
    public void setStorageLevel(StorageLevel storageLevel) { this.storageLevel = storageLevel; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public FeatureStatus getStatus() { return status; }
    public void setStatus(FeatureStatus status) { this.status = status; }

    public String getExtraParams() { return extraParams; }
    public void setExtraParams(String extraParams) { this.extraParams = extraParams; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    // ==================== 便捷方法 ====================

    public boolean isEnabled() {
        return status == FeatureStatus.ACTIVE;
    }

    /**
     * 获取用于分组的服务标识（从 calc_config 中提取，用于调度器分组）
     */
    public String getServiceKey() {
        CalcConfig cc = getCalcConfig();
        if (cc != null && cc.getService() != null && cc.getService().getServiceName() != null) {
            return cc.getService().getServiceName();
        }
        return featureCode; // fallback
    }

}
