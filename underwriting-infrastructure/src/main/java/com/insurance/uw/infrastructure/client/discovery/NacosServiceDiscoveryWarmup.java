package com.insurance.uw.infrastructure.client.discovery;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.domain.model.valueobject.CalcConfig;
import com.insurance.uw.domain.repository.FeatureConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动时收集 NACOS 服务发现相关的 namespace 配置并触发预热，
 * 将 SPI 冷启动开销从业务请求转移到应用启动阶段。
 */
@Component
public class NacosServiceDiscoveryWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NacosServiceDiscoveryWarmup.class);

    private final FeatureConfigRepository configRepository;
    private final NacosServiceDiscoveryStrategy nacosStrategy;

    public NacosServiceDiscoveryWarmup(FeatureConfigRepository configRepository,
                                       NacosServiceDiscoveryStrategy nacosStrategy) {
        this.configRepository = configRepository;
        this.nacosStrategy = nacosStrategy;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Set<String> namespaces = configRepository.findEnabledByCalcType(CalcType.EXTERNAL_API).stream()
                    .map(FeatureConfig::getCalcConfig)
                    .filter(Objects::nonNull)
                    .map(CalcConfig::getService)
                    .filter(Objects::nonNull)
                    .filter(svc -> "NACOS".equalsIgnoreCase(svc.getDiscoveryType()))
                    .map(svc -> svc.getNamespace() != null ? svc.getNamespace() : "DEFAULT")
                    .collect(Collectors.toSet());
            nacosStrategy.warmup(namespaces);
        } catch (Exception e) {
            log.warn("Nacos 预热配置收集失败: {}", e.getMessage());
        }
    }
}
