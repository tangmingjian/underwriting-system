package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 特征配置 MyBatis-Plus Mapper
 */
@Mapper
public interface FeatureConfigMapper extends BaseMapper<FeatureConfig> {
}
