package com.insurance.uw.engine.core.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.insurance.uw.engine.core.enums.FeatureStatus;

import java.time.LocalDateTime;

/**
 * 特征脚本实体 —— 映射 t_feature_script 表
 *
 * 独立存储 Groovy 出入参映射脚本，支持版本管理和跨特征复用。
 */
@TableName("t_feature_script")
public class FeatureScript {

    private Long id;
    private String scriptId;
    private String scriptName;
    private ScriptType scriptType;
    private String scriptText;
    private Integer version;
    private FeatureStatus status;
    private String description;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    public FeatureScript() {}

    // ==================== getters / setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getScriptId() { return scriptId; }
    public void setScriptId(String scriptId) { this.scriptId = scriptId; }

    public String getScriptName() { return scriptName; }
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }

    public ScriptType getScriptType() { return scriptType; }
    public void setScriptType(ScriptType scriptType) { this.scriptType = scriptType; }

    public String getScriptText() { return scriptText; }
    public void setScriptText(String scriptText) { this.scriptText = scriptText; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public FeatureStatus getStatus() { return status; }
    public void setStatus(FeatureStatus status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(LocalDateTime createdDate) { this.createdDate = createdDate; }

    public LocalDateTime getUpdatedDate() { return updatedDate; }
    public void setUpdatedDate(LocalDateTime updatedDate) { this.updatedDate = updatedDate; }

    // ==================== 枚举 ====================

    public enum ScriptType {
        INPUT,       // 入参映射
        OUTPUT,      // 出参映射
        EXPRESSION   // 表达式计算
    }

}
