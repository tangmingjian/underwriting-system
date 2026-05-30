package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 核保规则 MyBatis-Plus Mapper
 */
@Mapper
public interface UnderwritingRuleMapper extends BaseMapper<UnderwritingRule> {
}
