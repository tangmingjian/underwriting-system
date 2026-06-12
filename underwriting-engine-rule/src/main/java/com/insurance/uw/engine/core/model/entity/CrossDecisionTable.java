package com.insurance.uw.engine.core.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 交叉决策表 — 映射 t_cross_decision_table 表
 */
@TableName("t_cross_decision_table")
public class CrossDecisionTable {

    private Long id;
    private String tableCode;
    private String tableName;
    private String rowFeature;
    private String colFeature;
    private String cells;
    private Boolean defaultResult;
    private Integer status;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    public CrossDecisionTable() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTableCode() { return tableCode; }
    public void setTableCode(String tableCode) { this.tableCode = tableCode; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getRowFeature() { return rowFeature; }
    public void setRowFeature(String rowFeature) { this.rowFeature = rowFeature; }

    public String getColFeature() { return colFeature; }
    public void setColFeature(String colFeature) { this.colFeature = colFeature; }

    public String getCells() { return cells; }
    public void setCells(String cells) { this.cells = cells; }

    public Boolean getDefaultResult() { return defaultResult; }
    public void setDefaultResult(Boolean defaultResult) { this.defaultResult = defaultResult; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(LocalDateTime createdDate) { this.createdDate = createdDate; }

    public LocalDateTime getUpdatedDate() { return updatedDate; }
    public void setUpdatedDate(LocalDateTime updatedDate) { this.updatedDate = updatedDate; }

    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
