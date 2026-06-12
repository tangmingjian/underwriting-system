package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.uw.engine.core.model.entity.UnderwritingRuleHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 核保规则历史 MyBatis-Plus Mapper
 */
@Mapper
public interface UnderwritingRuleHistoryMapper extends BaseMapper<UnderwritingRuleHistory> {
}
