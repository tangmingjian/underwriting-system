package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.uw.engine.core.model.entity.CrossDecisionTable;
import org.apache.ibatis.annotations.Mapper;

/**
 * 交叉决策表 MyBatis-Plus Mapper
 */
@Mapper
public interface CrossDecisionTableMapper extends BaseMapper<CrossDecisionTable> {
}
