package com.insurance.uw.interfaces.controller;

import com.insurance.uw.application.service.FeatureConfigApplicationService;
import com.insurance.uw.engine.core.model.entity.FeatureScript;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 特征脚本管理 REST API
 */
@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    private final FeatureConfigApplicationService service;

    public ScriptController(FeatureConfigApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<FeatureScript> list() {
        return service.listScripts();
    }

    @GetMapping("/{scriptId}")
    public FeatureScript get(@PathVariable String scriptId) {
        return service.getScript(scriptId).orElse(null);
    }

    @PostMapping
    public String create(@RequestBody FeatureScript script) {
        service.saveScript(script);
        return "ok";
    }

    @PutMapping
    public String update(@RequestBody FeatureScript script) {
        service.updateScript(script);
        return "ok";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        service.deleteScript(id);
        return "ok";
    }

}
