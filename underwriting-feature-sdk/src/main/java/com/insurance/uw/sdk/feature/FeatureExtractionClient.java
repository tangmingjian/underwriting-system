package com.insurance.uw.sdk.feature;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 特征取数 Feign 客户端 — 供上游服务通过 Nacos + OpenFeign 远程调用
 */
@FeignClient(name = "underwriting-service", path = "/api/feature")
public interface FeatureExtractionClient {

    @PostMapping("/extract")
    FeatureExtractionResult extract(@RequestBody FeatureExtractionRequest request);
}
