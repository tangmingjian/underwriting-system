package com.insurance.uw.bootstrap;

import com.insurance.uw.application.rule.engine.ConditionListEvaluator;
import com.insurance.uw.application.rule.engine.CrossDecisionTableEvaluator;
import com.insurance.uw.application.rule.engine.RuleEngineFactory;
import com.insurance.uw.application.rule.engine.ScorecardEvaluator;
import com.insurance.uw.application.service.FeatureConfigApplicationService;
import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.application.feature.handler.CompositeCalcHandler;
import com.insurance.uw.application.feature.handler.CustomCalcHandler;
import com.insurance.uw.application.feature.handler.CustomFeatureHandler;
import com.insurance.uw.application.feature.handler.DatabaseQueryCalcHandler;
import com.insurance.uw.application.feature.handler.ExpressionCalcHandler;
import com.insurance.uw.application.feature.handler.ExternalApiCalcHandler;
import com.insurance.uw.application.feature.handler.FeatureCalcHandler;
import com.insurance.uw.application.feature.handler.ParamMappingCalcHandler;
import com.insurance.uw.application.feature.impl.FeatureExtractionServiceImpl;
import com.insurance.uw.application.service.FeatureExtractionService;
import com.insurance.uw.domain.repository.CrossDecisionTableRepository;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.repository.ScorecardConfigRepository;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.FeatureDependencyResolver;
import com.insurance.uw.domain.service.FeatureResultCache;
import com.insurance.uw.domain.service.GroovyMappingEngine;

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

    @Bean
    public FeatureDependencyResolver featureDependencyResolver() {
        return new FeatureDependencyResolver();
    }

    @Bean
    public FeatureExtractionService featureExtractionService(
            FeatureConfigRepository featureConfigRepository,
            FeatureDependencyResolver featureDependencyResolver,
            ExecutorService featureExecutor,
            List<FeatureCalcHandler> handlers,
            FeatureResultCache featureResultCache) {
        return new FeatureExtractionServiceImpl(featureConfigRepository, featureDependencyResolver,
                featureExecutor, handlers, featureResultCache);
    }

    @Bean
    public FeatureConfigApplicationService featureConfigApplicationService(
            FeatureConfigRepository repository,
            FeatureScriptRepository scriptRepository,
            GroovyMappingEngine groovyEngine) {
        return new FeatureConfigApplicationService(repository, scriptRepository, groovyEngine);
    }

    @Bean
    public ConditionListEvaluator conditionListEvaluator(ObjectMapper objectMapper) {
        return new ConditionListEvaluator(objectMapper);
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
    public RuleApplicationService ruleApplicationService(
            UnderwritingRuleRepository repository,
            RuleEngineFactory ruleEngineFactory) {
        return new RuleApplicationService(repository, ruleEngineFactory);
    }

    @Bean
    public ExternalApiCalcHandler externalApiCalcHandler(
            FeatureScriptRepository scriptRepository,
            GroovyMappingEngine groovyEngine,
            DownstreamApiClient apiClient) {
        return new ExternalApiCalcHandler(scriptRepository, groovyEngine, apiClient);
    }

    @Bean
    public ParamMappingCalcHandler paramMappingCalcHandler() {
        return new ParamMappingCalcHandler();
    }

    @Bean
    public ExpressionCalcHandler expressionCalcHandler() {
        return new ExpressionCalcHandler();
    }

    @Bean
    public DatabaseQueryCalcHandler databaseQueryCalcHandler() {
        return new DatabaseQueryCalcHandler();
    }

    @Bean
    public CompositeCalcHandler compositeCalcHandler() {
        return new CompositeCalcHandler();
    }

    @Bean
    public CustomCalcHandler customCalcHandler(List<CustomFeatureHandler> customHandlers) {
        return new CustomCalcHandler(customHandlers);
    }

}
