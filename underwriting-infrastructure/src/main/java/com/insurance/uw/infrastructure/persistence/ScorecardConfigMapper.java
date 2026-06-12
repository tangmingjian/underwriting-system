package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.uw.engine.core.model.entity.ScorecardConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评分卡配置 MyBatis-Plus Mapper
 */
@Mapper
public interface ScorecardConfigMapper extends BaseMapper<ScorecardConfig> {
}
