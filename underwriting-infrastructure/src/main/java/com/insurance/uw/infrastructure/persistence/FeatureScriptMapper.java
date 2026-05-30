package com.insurance.uw.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.uw.domain.model.entity.FeatureScript;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FeatureScriptMapper extends BaseMapper<FeatureScript> {
}
