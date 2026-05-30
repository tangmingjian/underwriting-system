package com.insurance.uw.interfaces.controller;

import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.application.service.RuleApplicationService.UnderwritingResult;
import com.insurance.uw.application.service.UnderwritingApplicationService;
import com.insurance.uw.domain.context.OrderFeatureContext;
import com.insurance.uw.domain.model.entity.Order;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 核保执行 REST API
 */
@RestController
@RequestMapping("/api/underwriting")
public class UnderwritingController {

    private final UnderwritingApplicationService underwritingService;
    private final RuleApplicationService ruleService;

    public UnderwritingController(UnderwritingApplicationService underwritingService,
                                   RuleApplicationService ruleService) {
        this.underwritingService = underwritingService;
        this.ruleService = ruleService;
    }

    /**
     * 提交订单执行核保（特征取数 + 规则评估）
     */
    @PostMapping("/evaluate")
    public List<UnderwritingResult> evaluate(@RequestBody Order order) {
        // 1. 执行特征取数
        OrderFeatureContext ctx = underwritingService.processOrder(order);

        // 2. 执行规则评估
        return ruleService.evaluate(ctx);
    }

    /**
     * 仅执行特征取数（不评估规则），返回上下文中的特征结果供调试
     */
    @PostMapping("/extract")
    public OrderFeatureContext extract(@RequestBody Order order) {
        return underwritingService.processOrder(order);
    }

}
