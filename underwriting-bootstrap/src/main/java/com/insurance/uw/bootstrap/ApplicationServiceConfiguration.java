package com.insurance.uw.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.uw.application.feature.impl.FeatureExtractionServiceImpl;
import com.insurance.uw.application.service.FeatureConfigApplicationService;
import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.engine.core.cache.CacheOps;
import com.insurance.uw.engine.core.handler.*;
import com.insurance.uw.engine.core.repository.*;
import com.insurance.uw.engine.core.rule.WordingResolver;
import com.insurance.uw.engine.core.rule.engine.ConditionListEvaluator;
import com.insurance.uw.engine.core.rule.engine.CrossDecisionTableEvaluator;
import com.insurance.uw.engine.core.rule.engine.RuleEngineFactory;
import com.insurance.uw.engine.core.rule.engine.ScorecardEvaluator;
import com.insurance.uw.engine.core.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 应用服务配置（在 bootstrap 模块组装依赖）
 */
@Configuration
public class ApplicationServiceConfiguration {

    // ==================== 引擎核心服务 ====================

    @Bean
    public FeatureDependencyResolver featureDependencyResolver() {
        return new FeatureDependencyResolver();
    }

    // ==================== 规则引擎 ====================

    @Bean
    public ConditionListEvaluator conditionListEvaluator(ObjectMapper objectMapper,
                                                         List<com.insurance.uw.engine.core.rule.engine.OperatorHandler> operatorHandlers) {
        return new ConditionListEvaluator(objectMapper, operatorHandlers);
    }

    @Bean
    public CrossDecisionTableEvaluator crossDecisionTableEvaluator(
            CrossDecisionTableRepository repository, ObjectMapper objectMapper) {
        return new CrossDecisionTableEvaluator(repository, objectMapper);
    }

    @Bean
    public ScorecardEvaluator scorecardEvaluator(
            ScorecardConfigRepository repository, ObjectMapper objectMapper) {
        return new ScorecardEvaluator(repository, objectMapper);
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

    // ==================== 特征计算处理器 ====================

    @Bean
    public ParamMappingCalcHandler paramMappingCalcHandler() {
        return new ParamMappingCalcHandler();
    }

    @Bean
    public ExpressionCalcHandler expressionCalcHandler(
            FeatureScriptRepository scriptRepo,
            GroovyMappingEngine groovyEngine) {
        return new ExpressionCalcHandler(scriptRepo, groovyEngine);
    }

    @Bean
    public ExternalApiCalcHandler externalApiCalcHandler(
            FeatureScriptRepository scriptRepo,
            GroovyMappingEngine groovyEngine,
            DownstreamApiClient apiClient) {
        return new ExternalApiCalcHandler(scriptRepo, groovyEngine, apiClient);
    }

    @Bean
    public CustomCalcHandler customCalcHandler(List<CustomFeatureHandler> customHandlers) {
        return new CustomCalcHandler(customHandlers);
    }

    // ==================== 桩 Handler ====================

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
            FeatureConfigRepository configRepo,
            FeatureDependencyResolver dependencyResolver,
            ExecutorService featureExecutor,
            List<FeatureCalcHandler> handlers,
            FeatureResultCache resultCache) {
        return new FeatureExtractionEngine(configRepo, dependencyResolver,
                featureExecutor, handlers, resultCache);
    }
}
