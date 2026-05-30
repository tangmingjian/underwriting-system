package com.insurance.uw.infrastructure.config;

import com.insurance.uw.infrastructure.client.DownstreamApiClientImpl;
import com.insurance.uw.infrastructure.client.MockDownstreamApiClient;
import com.insurance.uw.infrastructure.client.discovery.DirectServiceDiscoveryStrategy;
import com.insurance.uw.infrastructure.client.discovery.NacosServiceDiscoveryStrategy;
import com.insurance.uw.infrastructure.client.discovery.ServiceDiscoveryRouter;
import com.insurance.uw.infrastructure.client.discovery.ServiceDiscoveryStrategy;
import com.insurance.uw.infrastructure.client.discovery.StaticServiceDiscoveryStrategy;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.GroovyMappingEngine;
import com.insurance.uw.infrastructure.groovy.GroovyMappingEngineImpl;
import com.insurance.uw.infrastructure.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 核保系统基础设施配置
 */
@Configuration
public class UnderwritingConfiguration {

    // ==================== 基础设施 Bean ====================

    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public GroovyMappingEngine groovyMappingEngine() {
        return new GroovyMappingEngineImpl();
    }

    // ==================== 服务发现策略 ====================

    /**
     * 静态域名 / 直连策略（始终启用）
     */
    @Bean
    public DirectServiceDiscoveryStrategy directServiceDiscoveryStrategy() {
        return new DirectServiceDiscoveryStrategy();
    }

    /**
     * 静态多端点策略（STATIC 模式，始终启用）
     */
    @Bean
    public StaticServiceDiscoveryStrategy staticServiceDiscoveryStrategy() {
        return new StaticServiceDiscoveryStrategy();
    }

    /**
     * Nacos 服务发现策略（可通过配置 nacos.server-addr 启用）
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosServiceDiscoveryStrategy nacosServiceDiscoveryStrategy(
            @Value("${nacos.server-addr:127.0.0.1:8848}") String serverAddr) {
        return new NacosServiceDiscoveryStrategy(serverAddr);
    }

    /**
     * 服务发现路由器 —— 聚合所有策略，按 discoveryType 分发
     */
    @Bean
    public ServiceDiscoveryRouter serviceDiscoveryRouter(List<ServiceDiscoveryStrategy> strategies) {
        return new ServiceDiscoveryRouter(new ArrayList<>(strategies));
    }

    // ==================== API 客户端 ====================

    @Bean
    @Profile("!mock")
    public DownstreamApiClient downstreamApiClient(RestTemplate restTemplate,
                                                    ServiceDiscoveryRouter router) {
        return new DownstreamApiClientImpl(restTemplate, router);
    }

    @Bean
    @Profile("mock")
    public DownstreamApiClient mockDownstreamApiClient() {
        return new MockDownstreamApiClient();
    }

    // ==================== 特征取数线程池 ====================

    @Bean
    public ExecutorService featureExecutor(
            @Value("${underwriting.executor.core-pool-size:8}") int corePoolSize,
            @Value("${underwriting.executor.max-pool-size:16}") int maxPoolSize,
            @Value("${underwriting.executor.queue-capacity:200}") int queueCapacity,
            @Value("${underwriting.executor.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${underwriting.executor.thread-name-prefix:uw-feature-}") String prefix) {
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> new Thread(r, prefix + r.hashCode()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // ==================== 仓储 Bean ====================

    @Bean
    public FeatureConfigRepository featureConfigRepository(FeatureConfigMapper mapper) {
        return new FeatureConfigRepositoryImpl(mapper);
    }

    @Bean
    public UnderwritingRuleRepository underwritingRuleRepository(UnderwritingRuleMapper mapper) {
        return new UnderwritingRuleRepositoryImpl(mapper);
    }

    @Bean
    public FeatureScriptRepository featureScriptRepository(FeatureScriptMapper mapper) {
        return new FeatureScriptRepositoryImpl(mapper);
    }

}
