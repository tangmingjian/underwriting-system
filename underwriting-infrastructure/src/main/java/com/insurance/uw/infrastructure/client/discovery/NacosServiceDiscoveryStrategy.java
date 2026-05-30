package com.insurance.uw.infrastructure.client.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.insurance.uw.domain.model.valueobject.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Nacos 服务发现策略
 *
 * 通过 Nacos NamingService 动态发现服务实例，
 * 支持按 namespace + group + serviceName 定位。
 */
public class NacosServiceDiscoveryStrategy implements ServiceDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(NacosServiceDiscoveryStrategy.class);

    private final String serverAddr;

    /** 缓存 NamingService 实例，按 namespace 区分 */
    private final ConcurrentMap<String, NamingService> namingServiceCache = new ConcurrentHashMap<>();

    public NacosServiceDiscoveryStrategy(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    @Override
    public boolean supports(String discoveryType) {
        return "NACOS".equalsIgnoreCase(discoveryType);
    }

    @Override
    public String resolveUrl(ServiceConfig config) {
        String serviceName = config.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("NACOS 模式下 service.serviceName 不能为空");
        }

        String group = config.getGroup() != null ? config.getGroup() : "DEFAULT_GROUP";
        String namespace = config.getNamespace();

        try {
            NamingService namingService = getOrCreateNamingService(namespace);

            // 选一个健康实例
            Instance instance = namingService.selectOneHealthyInstance(serviceName, group);

            if (instance == null) {
                throw new RuntimeException("NACOS 中未找到健康实例: service=" + serviceName
                        + ", group=" + group + ", namespace=" + namespace);
            }

            String host = instance.getIp();
            int port = instance.getPort();
            String protocol = config.getProtocol() != null ? config.getProtocol().toLowerCase() : "http";
            String path = config.getPath() != null ? config.getPath() : "";

            String url = protocol + "://" + host + ":" + port + path;

            log.debug("NACOS 服务发现: {}:{} → {}", serviceName, group, url);
            return url;

        } catch (NacosException e) {
            throw new RuntimeException("NACOS 服务发现失败: service=" + serviceName
                    + ", group=" + group, e);
        }
    }

    /**
     * 获取或创建 NamingService（按 namespace 缓存）
     */
    private NamingService getOrCreateNamingService(String namespace) throws NacosException {
        String cacheKey = namespace != null ? namespace : "DEFAULT";
        return namingServiceCache.computeIfAbsent(cacheKey, ns -> {
            try {
                Properties props = new Properties();
                props.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
                if (namespace != null && !namespace.isBlank()) {
                    props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
                }
                return NacosFactory.createNamingService(props);
            } catch (NacosException e) {
                throw new RuntimeException("创建 Nacos NamingService 失败: serverAddr="
                        + serverAddr + ", namespace=" + namespace, e);
            }
        });
    }

}
