package com.insurance.uw.bootstrap;

import com.insurance.uw.application.service.FeatureConfigApplicationService;
import com.insurance.uw.application.service.RuleApplicationService;
import com.insurance.uw.feature.api.FeatureExtractionService;
import com.insurance.uw.feature.core.handler.CompositeCalcHandler;
import com.insurance.uw.feature.core.handler.DatabaseQueryCalcHandler;
import com.insurance.uw.feature.core.handler.ExpressionCalcHandler;
import com.insurance.uw.feature.core.handler.ExternalApiCalcHandler;
import com.insurance.uw.feature.core.handler.FeatureCalcHandler;
import com.insurance.uw.feature.core.handler.ParamMappingCalcHandler;
import com.insurance.uw.feature.core.impl.FeatureExtractionServiceImpl;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import com.insurance.uw.domain.repository.FeatureScriptRepository;
import com.insurance.uw.domain.repository.UnderwritingRuleRepository;
import com.insurance.uw.domain.service.DownstreamApiClient;
import com.insurance.uw.domain.service.GroovyMappingEngine;

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
    public FeatureExtractionService featureExtractionService(
            FeatureConfigRepository featureConfigRepository,
            ExecutorService featureExecutor,
            List<FeatureCalcHandler> handlers) {
        return new FeatureExtractionServiceImpl(featureConfigRepository, featureExecutor, handlers);
    }

    @Bean
    public FeatureConfigApplicationService featureConfigApplicationService(
            FeatureConfigRepository repository,
            FeatureScriptRepository scriptRepository,
            GroovyMappingEngine groovyEngine) {
        return new FeatureConfigApplicationService(repository, scriptRepository, groovyEngine);
    }

    @Bean
    public RuleApplicationService ruleApplicationService(UnderwritingRuleRepository repository) {
        return new RuleApplicationService(repository);
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

}
