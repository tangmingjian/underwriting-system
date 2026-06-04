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
 * 核保执行 REST API（向下兼容端点）
 */
@RestController
@RequestMapping("/api/underwriting")
public class UnderwritingController {

    private final FeatureExtractionService featureService;
    private final RuleApplicationService ruleService;

    public UnderwritingController(FeatureExtractionService featureService,
                                   RuleApplicationService ruleService) {
        this.featureService = featureService;
        this.ruleService = ruleService;
    }

    /**
     * 提交订单执行核保（特征取数 + 规则评估）
     */
    @PostMapping("/evaluate")
    public List<UnderwritingResult> evaluate(@RequestBody Order order) {
        FeatureExtractionRequest request = ruleService.buildExtractionRequest(order);
        FeatureExtractionResult result = featureService.extract(request);
        return ruleService.evaluate(order, result);
    }

    /**
     * 仅执行特征取数（不评估规则），返回扁平化结果供调试
     */
    @PostMapping("/extract")
    public FeatureExtractionResult extract(@RequestBody Order order) {
        FeatureExtractionRequest request = ruleService.buildExtractionRequest(order);
        return featureService.extract(request);
    }

}
