package com.insurance.uw.interfaces.controller;

import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.sdk.feature.FeatureExtractionRequest;
import com.insurance.uw.sdk.feature.FeatureExtractionResult;
import org.springframework.web.bind.annotation.*;

/**
 * 特征取数 REST API（后期可独立部署本端点）
 */
@RestController
@RequestMapping("/api/feature")
public class FeatureExtractionController {

    private final FeatureExtractionService featureService;

    public FeatureExtractionController(FeatureExtractionService featureService) {
        this.featureService = featureService;
    }

    /**
     * 独立执行特征取数，返回扁平化结果
     */
    @PostMapping("/extract")
    public FeatureExtractionResult extract(@RequestBody FeatureExtractionRequest request) {
        return featureService.extract(request);
    }
}
