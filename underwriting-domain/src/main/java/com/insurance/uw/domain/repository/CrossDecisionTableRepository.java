package com.insurance.uw.domain.repository;

import com.insurance.uw.domain.model.entity.CrossDecisionTable;

import java.util.List;
import java.util.Optional;

/**
 * 交叉决策表仓储接口
 */
public interface CrossDecisionTableRepository {

    Optional<CrossDecisionTable> findByTableCode(String tableCode);

    List<CrossDecisionTable> findAllEnabled();

    void save(CrossDecisionTable table);

    void update(CrossDecisionTable table);

    void delete(Long id);
}
