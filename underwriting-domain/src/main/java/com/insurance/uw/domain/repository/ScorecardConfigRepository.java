package com.insurance.uw.domain.repository;

import com.insurance.uw.domain.model.entity.ScorecardConfig;

import java.util.List;
import java.util.Optional;

/**
 * 评分卡配置仓储接口
 */
public interface ScorecardConfigRepository {

    Optional<ScorecardConfig> findByScorecardCode(String scorecardCode);

    List<ScorecardConfig> findAllEnabled();

    void save(ScorecardConfig config);

    void update(ScorecardConfig config);

    void delete(Long id);
}
