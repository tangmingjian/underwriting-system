package com.insurance.uw.interfaces.controller;

import com.insurance.uw.application.service.FeatureConfigApplicationService;
import com.insurance.uw.engine.core.model.entity.FeatureConfig;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 特征配置管理 REST API
 */
@RestController
@RequestMapping("/api/features")
public class FeatureConfigController {

    private final FeatureConfigApplicationService service;

    public FeatureConfigController(FeatureConfigApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<FeatureConfig> list() {
        return service.listAll();
    }

    @GetMapping("/{code}")
    public FeatureConfig get(@PathVariable String code) {
        return service.getByCode(code).orElse(null);
    }

    @PostMapping
    public String create(@RequestBody FeatureConfig config) {
        service.create(config);
        return "ok";
    }

    @PutMapping
    public String update(@RequestBody FeatureConfig config) {
        service.update(config);
        return "ok";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        service.delete(id);
        return "ok";
    }

    @PostMapping("/{code}/evict-cache")
    public String evictCache(@PathVariable String code) {
        service.evictCache(code);
        return "cache evicted for " + code;
    }

}
