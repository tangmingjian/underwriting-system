package com.insurance.uw.engine.core.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 评分卡配置 — 映射 t_scorecard_config 表
 */
@TableName("t_scorecard_config")
public class ScorecardConfig {

    private Long id;
    private String scorecardCode;
    private String scorecardName;
    private String dimensions;
    private String scoringFormula;
    private String buckets;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public ScorecardConfig() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getScorecardCode() { return scorecardCode; }
    public void setScorecardCode(String scorecardCode) { this.scorecardCode = scorecardCode; }

    public String getScorecardName() { return scorecardName; }
    public void setScorecardName(String scorecardName) { this.scorecardName = scorecardName; }

    public String getDimensions() { return dimensions; }
    public void setDimensions(String dimensions) { this.dimensions = dimensions; }

    public String getScoringFormula() { return scoringFormula; }
    public void setScoringFormula(String scoringFormula) { this.scoringFormula = scoringFormula; }

    public String getBuckets() { return buckets; }
    public void setBuckets(String buckets) { this.buckets = buckets; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
