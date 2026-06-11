package com.insurance.uw.bootstrap;

import com.insurance.uw.application.service.FeatureConfigApplicationService;
import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.application.feature.impl.FeatureExtractionServiceImpl;
import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.bootstrap.adapter.*;
import com.insurance.uw.domain.repository.*;
import com.insurance.uw.engine.core.handler.*;
import com.insurance.uw.engine.core.rule.WordingResolver;
import com.insurance.uw.engine.core.rule.engine.*;
import com.insurance.uw.engine.core.service.FeatureDependencyResolver;
import com.insurance.uw.engine.core.service.FeatureExtractionEngine;
import com.insurance.uw.engine.core.service.FeatureResultCache;
import com.insurance.uw.engine.core.service.GroovyMappingEngine;
import com.insurance.uw.engine.core.service.DownstreamApiClient;
import com.insurance.uw.engine.core.cache.CacheOps;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用服务配置（在 bootstrap 模块组装依赖）
 */
@Configuration
public class ApplicationServiceConfiguration {

    // ==================== 引擎适配器（Repository 仍需适配） ====================

    @Bean
    public FeatureConfigRepositoryAdapter featureConfigRepositoryAdapter(
            FeatureConfigRepository domainRepo) {
        return new FeatureConfigRepositoryAdapter(domainRepo);
    }

    @Bean
    public FeatureScriptRepositoryAdapter featureScriptRepositoryAdapter(
            FeatureScriptRepository domainRepo) {
        return new FeatureScriptRepositoryAdapter(domainRepo);
    }

    @Bean
    public CrossDecisionTableRepositoryAdapter crossDecisionTableRepositoryAdapter(
            CrossDecisionTableRepository domainRepo) {
        return new CrossDecisionTableRepositoryAdapter(domainRepo);
    }

    @Bean
    public ScorecardConfigRepositoryAdapter scorecardConfigRepositoryAdapter(
            ScorecardConfigRepository domainRepo) {
        return new ScorecardConfigRepositoryAdapter(domainRepo);
    }

    // ==================== 引擎核心服务 ====================

    @Bean
    public FeatureDependencyResolver featureDependencyResolver() {
        return new FeatureDependencyResolver();
    }

    // ==================== 规则引擎（engine-core 版） ====================

    @Bean
    public ConditionListEvaluator conditionListEvaluator(ObjectMapper objectMapper,
                                                          List<com.insurance.uw.engine.core.rule.engine.OperatorHandler> operatorHandlers) {
        return new ConditionListEvaluator(objectMapper, operatorHandlers);
    }

    @Bean
    public CrossDecisionTableEvaluator crossDecisionTableEvaluator(
            CrossDecisionTableRepositoryAdapter repositoryAdapter, ObjectMapper objectMapper) {
        return new CrossDecisionTableEvaluator(repositoryAdapter, objectMapper);
    }

    @Bean
    public ScorecardEvaluator scorecardEvaluator(
            ScorecardConfigRepositoryAdapter repositoryAdapter, ObjectMapper objectMapper) {
        return new ScorecardEvaluator(repositoryAdapter, objectMapper);
    }

    @Bean
    public RuleEngineFactory ruleEngineFactory(
            ConditionListEvaluator cle,
            CrossDecisionTableEvaluator cdte,
            ScorecardEvaluator se) {
        return new RuleEngineFactory(cle, cdte, se);
    }

    @Bean
    public WordingResolver wordingResolver(ObjectMapper objectMapper) {
        return new WordingResolver(objectMapper);
    }

    // ==================== 应用服务 ====================

    @Bean
    public FeatureExtractionService featureExtractionService(
            FeatureExtractionEngine engine) {
        return new FeatureExtractionServiceImpl(engine);
    }

    @Bean
    public FeatureConfigApplicationService featureConfigApplicationService(
            FeatureConfigRepository repository,
            FeatureScriptRepository scriptRepository,
            GroovyMappingEngine groovyEngine) {
        return new FeatureConfigApplicationService(repository, scriptRepository, groovyEngine);
    }

    @Bean
    public RuleApplicationService ruleApplicationService(
            UnderwritingRuleRepository repository,
            RuleEngineFactory ruleEngineFactory,
            WordingResolver wordingResolver) {
        return new RuleApplicationService(repository, ruleEngineFactory, wordingResolver);
    }

    @Bean
    public CacheManagementService cacheManagementService(
            CacheOps cacheOps,
            GroovyMappingEngine groovyEngine,
            FeatureResultCache featureResultCache) {
        return new CacheManagementService(cacheOps, groovyEngine, featureResultCache);
    }

    // ==================== 特征计算处理器（engine-core 版） ====================

    @Bean
    public ParamMappingCalcHandler paramMappingCalcHandler() {
        return new ParamMappingCalcHandler();
    }

    @Bean
    public ExpressionCalcHandler expressionCalcHandler(
            FeatureScriptRepositoryAdapter scriptRepoAdapter,
            GroovyMappingEngine groovyEngine) {
        return new ExpressionCalcHandler(scriptRepoAdapter, groovyEngine);
    }

    @Bean
    public ExternalApiCalcHandler externalApiCalcHandler(
            FeatureScriptRepositoryAdapter scriptRepoAdapter,
            GroovyMappingEngine groovyEngine,
            DownstreamApiClient apiClient) {
        return new ExternalApiCalcHandler(scriptRepoAdapter, groovyEngine, apiClient);
    }

    @Bean
    public CustomCalcHandler customCalcHandler(List<CustomFeatureHandler> customHandlers) {
        return new CustomCalcHandler(customHandlers);
    }

    // ==================== 桩 Handler（engine-core 版） ====================

    @Bean
    public DatabaseQueryCalcHandler databaseQueryCalcHandler() {
        return new DatabaseQueryCalcHandler();
    }

    @Bean
    public CompositeCalcHandler compositeCalcHandler() {
        return new CompositeCalcHandler();
    }

    // ==================== 特征取数引擎 ====================

    @Bean
    public FeatureExtractionEngine featureExtractionEngine(
            FeatureConfigRepositoryAdapter configRepoAdapter,
            FeatureDependencyResolver dependencyResolver,
            ExecutorService featureExecutor,
            List<FeatureCalcHandler> handlers,
            FeatureResultCache resultCache) {
        return new FeatureExtractionEngine(configRepoAdapter, dependencyResolver,
                featureExecutor, handlers, resultCache);
    }
}
