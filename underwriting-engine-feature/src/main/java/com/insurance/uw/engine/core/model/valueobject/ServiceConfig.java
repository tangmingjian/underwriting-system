package com.insurance.uw.engine.core.model.valueobject;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下游服务发现与调用配置（calc_config.service 部分）
 *
 * JSON 字段使用 snake_case，Java 字段使用 camelCase，通过 @JsonProperty 映射。
 */
public class ServiceConfig {

    /** 服务发现类型：NACOS / STATIC / DIRECT */
    @JsonProperty("discovery_type")
    private String discoveryType;

    /** NACOS 服务名 */
    @JsonProperty("service_name")
    private String serviceName;

    /** NACOS 命名空间 */
    @JsonProperty("namespace")
    private String namespace;

    /** NACOS 分组 */
    @JsonProperty("group")
    private String group;

    /** 静态域名列表（STATIC 模式） */
    @JsonProperty("static_endpoints")
    private List<String> staticEndpoints = new ArrayList<>();

    /** 协议：HTTP / HTTPS */
    @JsonProperty("protocol")
    private String protocol;

    /** 接口路径 */
    @JsonProperty("path")
    private String path;

    /** HTTP 方法 */
    @JsonProperty("method")
    private String method;

    /** 超时（毫秒） */
    @JsonProperty("timeout_ms")
    private Integer timeoutMs;

    /** 请求头 */
    @JsonProperty("headers")
    private Map<String, String> headers = new HashMap<>();

    public ServiceConfig() {}

    public String getDiscoveryType() { return discoveryType; }
    public void setDiscoveryType(String discoveryType) { this.discoveryType = discoveryType; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public List<String> getStaticEndpoints() { return staticEndpoints; }
    public void setStaticEndpoints(List<String> staticEndpoints) { this.staticEndpoints = staticEndpoints; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

}
