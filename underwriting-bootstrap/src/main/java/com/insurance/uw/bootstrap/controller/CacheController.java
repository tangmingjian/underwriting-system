package com.insurance.uw.bootstrap.controller;

import com.insurance.uw.bootstrap.CacheManagementService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缓存管理 REST API
 */
@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final CacheManagementService service;

    public CacheController(CacheManagementService service) {
        this.service = service;
    }

    /**
     * 一键清除所有缓存（特征 + 规则 + 脚本 + 计算结果）。
     */
    @PostMapping("/clear-all")
    public String clearAll() {
        return service.clearAll();
    }
}
