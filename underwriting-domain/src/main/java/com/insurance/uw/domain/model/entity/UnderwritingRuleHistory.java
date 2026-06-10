package com.insurance.uw.domain.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.insurance.uw.common.enums.ChangeType;
import com.insurance.uw.common.enums.EvalType;
import com.insurance.uw.common.enums.RuleType;

import java.time.LocalDateTime;

/**
 * 核保规则历史归档 — 映射 t_underwriting_rule_history 表
 */
@TableName("t_underwriting_rule_history")
public class UnderwritingRuleHistory {

    private Long historyId;
    private Long id;
    private String ruleCode;
    private String ruleName;
    private RuleType ruleType;
    private String expression;
    private EvalType evalType;
    private String featureCodes;
    private String productCode;
    private Integer priority;
    private Integer status;
    private Integer version;
    private ChangeType changeType;
    private LocalDateTime changedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String wordingConfig;

    public UnderwritingRuleHistory() {}

    public static UnderwritingRuleHistory from(UnderwritingRule rule, ChangeType changeType) {
        UnderwritingRuleHistory h = new UnderwritingRuleHistory();
        h.id = rule.getId();
        h.ruleCode = rule.getRuleCode();
        h.ruleName = rule.getRuleName();
        h.ruleType = rule.getRuleType();
        h.expression = rule.getExpression();
        h.evalType = rule.getEvalType();
        h.featureCodes = rule.getFeatureCodes();
        h.productCode = rule.getProductCode();
        h.priority = rule.getPriority();
        h.status = rule.getStatus();
        h.version = rule.getVersion();
        h.changeType = changeType;
        h.changedAt = LocalDateTime.now();
        h.createTime = rule.getCreateTime();
        h.updateTime = rule.getUpdateTime();
        h.wordingConfig = rule.getWordingConfig();
        return h;
    }

    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public RuleType getRuleType() { return ruleType; }
    public void setRuleType(RuleType ruleType) { this.ruleType = ruleType; }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }

    public EvalType getEvalType() { return evalType; }
    public void setEvalType(EvalType evalType) { this.evalType = evalType; }

    public String getFeatureCodes() { return featureCodes; }
    public void setFeatureCodes(String featureCodes) { this.featureCodes = featureCodes; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public String getWordingConfig() { return wordingConfig; }
    public void setWordingConfig(String wordingConfig) { this.wordingConfig = wordingConfig; }

}
