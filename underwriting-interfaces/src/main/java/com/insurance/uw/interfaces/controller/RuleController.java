package com.insurance.uw.interfaces.controller;

import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.domain.model.entity.UnderwritingRule;
import com.insurance.uw.domain.model.entity.UnderwritingRuleHistory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 核保规则管理 REST API
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleApplicationService service;

    public RuleController(RuleApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<UnderwritingRule> list() {
        return service.listAll();
    }

    @GetMapping("/{code}")
    public UnderwritingRule get(@PathVariable String code) {
        return service.getByCode(code).orElse(null);
    }

    @PostMapping
    public String create(@RequestBody UnderwritingRule rule) {
        service.create(rule);
        return "ok";
    }

    @PutMapping
    public String update(@RequestBody UnderwritingRule rule) {
        service.update(rule);
        return "ok";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        service.delete(id);
        return "ok";
    }

    @GetMapping("/{code}/history")
    public List<UnderwritingRuleHistory> getHistory(@PathVariable String code) {
        return service.getHistory(code);
    }

}
