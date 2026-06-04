package com.insurance.uw.interfaces.controller;

import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.application.service.RuleApplicationService.UnderwritingResult;
import com.insurance.uw.domain.model.entity.Order;
import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 规则评估 REST API — 独立端点：请求构建 → 取数 → 评估
 */
@RestController
@RequestMapping("/api/rule")
public class RuleEvaluationController {

    private final RuleApplicationService ruleService;
    private final FeatureExtractionService featureService;

    public RuleEvaluationController(RuleApplicationService ruleService,
                                     FeatureExtractionService featureService) {
        this.ruleService = ruleService;
        this.featureService = featureService;
    }

    /**
     * 执行规则评估（含特征取数）
     */
    @PostMapping("/evaluate")
    public List<UnderwritingResult> evaluate(@RequestBody Order order) {
        FeatureExtractionRequest request = ruleService.buildExtractionRequest(order);
        FeatureExtractionResult result = featureService.extract(request);
        return ruleService.evaluate(order, result);
    }
}
