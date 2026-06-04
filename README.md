# Underwriting System — 特征取数引擎

基于 DDD 架构的规则引擎特征取数服务，负责按需从订单数据、外部 API、数据库等来源提取特征值，供核保规则评估使用。

## 架构概览

```
Request (FeatureExtractionRequest)
  ├── Order（订单对象，含保单/被保人/投保人）
  ├── policyInsuredFeatureMap（保单→被保人→需要的特征码）
  └── policyApplicantFeatureMap（保单→投保人→需要的特征码）
         │
         ▼
FeatureExtractionController  (POST /api/feature/extract)
         │
         ▼
FeatureExtractionServiceImpl (4 阶段流水线)
         │
    ┌────┴────┐
    │ 上下文树  │  OrderFeatureContext
    │         │    ├── PolicyFeatureContext × N
    │         │    │     ├── InsuredFeatureContext × N
    │         │    │     └── ApplicantFeatureContext
    │         │    └── orderFeatures / FeatureTargeting
    └────┬────┘
         │
         ▼
FeatureExtractionResult (扁平化输出)
  ├── orderFeatures
  ├── policyFeatures (policyId → {fc: val})
  ├── insuredFeatures (policyId → insuredId → {fc: val})
  └── applicantFeatures (policyId → applicantId → {fc: val})
```

## API 入口

**`POST /api/feature/extract`**

请求体 `FeatureExtractionRequest`:
| 字段 | 类型 | 说明 |
|------|------|------|
| `order` | `Order` | 订单对象（含保单、被保人、投保人） |
| `policyInsuredFeatureMap` | `Map<policyId, Map<insuredId, Set<featureCode>>>` | 各保单下各被保人需要哪些特征码 |
| `policyApplicantFeatureMap` | `Map<policyId, Map<applicantId, Set<featureCode>>>` | 各保单下各投保人需要哪些特征码 |

响应体 `FeatureExtractionResult`（详情见下文"输出结构"）。

## 完整执行流程（4 阶段流水线）

入口方法 `FeatureExtractionServiceImpl.extract(request)` 按以下顺序执行：

### Phase 1: 构建上下文树

```
OrderFeatureContext orderCtx = new OrderFeatureContext(order)
```

根据 `Order` 对象递归构建四级上下文树：
- **OrderFeatureContext** — 顶层上下文，持有 `List<PolicyFeatureContext>`、订单级特征 Map `{fc → value}`
- **PolicyFeatureContext** — 保单上下文，持有 `ApplicantFeatureContext`、`List<InsuredFeatureContext>`、保单级特征 Map
- **InsuredFeatureContext** — 被保人上下文，持有被保人特征 Map `acquiredFeatures`
- **ApplicantFeatureContext** — 投保人上下文，持有投保人特征 Map `features`

同时将请求中的 `policyInsuredFeatureMap` 和 `policyApplicantFeatureMap` 注入 `FeatureTargeting` 对象，挂载到 `orderCtx` 上。`FeatureTargeting` 是整个执行过程的"导航地图"，后续每一步都依赖它进行按需过滤。

### Phase 2: 配置加载 + 依赖展开

```java
Map<String, FeatureConfig> configMap = loadConfigsWithDependencies(requestedCodes);
```

1. 从 `FeatureExtractionRequest` 汇总所有请求的特征码
2. **BFS 批次加载**：从数据库查出特征码对应的 `FeatureConfig` 配置，同时读取 `dependsOn` 字段
3. 若特征 A 依赖 B，则 B 也被加入待加载集合，继续查询直到收敛
4. 最终得到包含所有请求特征 + 传递依赖的完整 `configMap`

`FeatureConfig` 关键字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `featureCode` | String | 特征码 |
| `calcType` | CalcType | 计算类型（决定用哪个 Handler 执行） |
| `aggregation` | AggregationLevel | 聚合级别（以什么粒度执行计算） |
| `storageLevel` | StorageLevel | 存储级别（计算结果存到上下文树的哪一层） |
| `dependsOn` | List\<String\> | 依赖的特征码列表 |
| `calcConfig` | CalcConfig | 计算配置 JSON（含 source、service、入参/出参脚本 ID 等） |
| `ttlSeconds` | Integer | 缓存 TTL（秒） |

### Phase 3: 构建派生映射

```java
ft.buildDerivedMaps(configMap);
```

`FeatureTargeting` 在配置加载完成后，基于 `dependsOn` 关系构建三张派生索引：

1. **featureInsuredTargetMap** (`featureCode → Set<insuredId>`) — 哪些被保人需要某特征（含依赖传播）
2. **featurePolicyTargetMap** (`featureCode → Set<policyId>`) — 哪些保单需要某特征（含依赖传播）
3. **featureInsuredPolicyMap** (`featureCode → insuredId → Set<policyId>`) — 为 ORDER 聚合级别、被保人存储级别的跨保单精确路由提供依据

依赖传播的核心逻辑：若特征 A 依赖 B，则 A 的所有 (insured, policy) 目标也加入 B 的目标集合，循环直到收敛。

### Phase 4: 拓扑排序 → 分层执行

```java
List<Set<String>> layers = dependencyResolver.topoSort(expandedCodes, configMap);
for (Set<String> layer : layers) {
    executeLayer(orderCtx, layer, configMap, dispatcher);
}
```

#### 4a. 拓扑分层

`FeatureDependencyResolver` 使用 **Kahn 算法**对特征码进行拓扑排序：

- 入度 = 特征所依赖的其他特征数量
- 无依赖的特征入度为 0，进入第一层
- **同层内可并发执行**（无相互依赖）
- 依赖方向校验：只能从窄聚合范围依赖宽聚合范围（INSURED 可以依赖 ORDER，反之报错）

#### 4b. 层内执行

每层执行时，先按 `AggregationLevel` 分为四组，每组按不同的执行上下文并发执行：

| 聚合级别 | 执行上下文 | 执行粒度 |
|----------|-----------|---------|
| ORDER | `OrderFeatureContext` | 整个订单执行 **1 次** |
| POLICY | `PolicyFeatureContext` | **每个保单**独立执行 |
| INSURED | `InsuredFeatureContext` | **每个被保人**独立执行 |
| APPLICANT | `ApplicantFeatureContext` | **每个投保人**独立执行 |

每个级别执行时，通过 `FeatureTargeting` 的按需过滤机制，跳过不需要该特征的目标实体。

#### 4c. 按需过滤机制

以 ORDER 级聚合为例：

```
executeOrderLayer:
  1. 汇总所有保单下所有被保人/投保人需要的特征码 → needed
  2. 过滤本层特征：ft.isFeatureTargeted(fc)
     → 该特征至少有一个实体对象需要才计算，否则跳过
  3. 执行分组 → executeGroups(orderCtx, filteredCodes, configMap, needed, futures, dispatcher)
```

`executeGroups` 内部再按 `calcType + serviceKey` 二次分组：
- **同服务合并**：同一外部服务的多个特征合并为一次 HTTP 调用（批处理优化）
- **过滤执行**：`needed` 不为 null 时，只执行该上下文中实际需要的特征

#### 4d. Handler 调度与并发

```java
// PARAM_MAPPING 同步执行（纯 CPU 无 I/O，避免线程池调度开销）
// 其他类型通过 CompletableFuture.runAsync 并发执行
dispatchFeature(cfg, futures, () -> executeOne(ctx, cfg, dispatcher));
```

对于 `EXTERNAL_API` 类型且多个特征属于同一服务 → 合并为一次 `executeBatch` 调用：
1. 对每个特征分别调用 Groovy `buildRequest` 脚本拼装参数
2. **深度合并**所有参数为一个请求体
3. 只发 **一次 HTTP 请求**
4. 分别用各特征的 Groovy `extractFeatures` 脚本从响应中提取结果

## 计算类型（CalcType）

| 类型 | Handler | 状态 | 说明 |
|------|---------|------|------|
| `PARAM_MAPPING` | `ParamMappingCalcHandler` | 已实现 | 通过反射从上下文实体（Order/Policy/Insured/Applicant）直接读取字段值 |
| `EXTERNAL_API` | `ExternalApiCalcHandler` | 已实现 | 加载 Groovy 脚本拼装请求 → HTTP 调用下游 → Groovy 脚本提取响应 |
| `EXPRESSION` | `ExpressionCalcHandler` | 桩 | 基于 SpEL 表达式计算（待实现） |
| `DATABASE_QUERY` | `DatabaseQueryCalcHandler` | 桩 | 直接查库获取（待实现） |
| `COMPOSITE` | `CompositeCalcHandler` | 桩 | 组合多个子特征（待实现） |

### PARAM_MAPPING 详解

通过配置 `calc_config.source` 字段，格式为 `{entityType}.{fieldName}`：

```
source: "insured.age"      → 读取被保人的 age 字段
source: "order.channel"    → 读取订单的 channel 字段
source: "policy.product.productCode"  → 读取保单的 product.productCode 嵌套字段
source: "feature.BASE_RISK.riskScore" → 从已计算的特征 BASE_RISK 中提取 riskScore 子字段
```

根据执行上下文的聚合级别不同，返回结果的 key 约定也不同：

| 上下文级别 × entityType | 结果 key |
|-------------------------|---------|
| ORDER × order | `__ORDER__` + 每个 policyId 副本（支持向下路由） |
| ORDER × policy | `policyId` |
| ORDER × insured | `insuredId` |
| ORDER × applicant | `policyId`（dispatcher 通过 policyId 查找 applicant） |
| POLICY × order | `__ORDER__` |
| POLICY × policy | `policyId` |
| POLICY × insured | `insuredId` |
| POLICY × applicant | `applicantId` |
| INSURED × insured | `_self_` |
| APPLICANT × applicant | `_self_` |

### EXTERNAL_API 详解

通过 Groovy 脚本实现请求构建和响应提取：

1. 从 `calc_config` 中获取 `inputScriptId`、`outputScriptId` 和 `service` 配置
2. 加载入参 Groovy 脚本，调用 `buildRequest(ctx)` → 得到请求参数 Map
3. 通过 `DownstreamApiClient` 调用配置的目标服务（支持 Nacos 服务发现 / Static IP / Direct URL）
4. 加载出参 Groovy 脚本，调用 `extractFeatures(response, ctx)` → 得到 `Map<targetId, Map<featureName, value>>`
5. 用 `featureCode` 包裹结果，返回 `Map<targetId, {featureCode: featureData}>`

**批处理优化**：同层同一服务的多个 EXTERNAL_API 特征，buildRequest 的输出通过 `deepMerge` 合并为单个请求，一次 HTTP 调用拿回所有特征数据。

## 路由矩阵（FeatureResultDispatcher）

Handler 返回的标准化结果 Map 由 `FeatureResultDispatcher` 根据 **AggregationLevel × StorageLevel** 路由到上下文树的正确位置：

```
             │  Storage →
Aggregation  │  ORDER    POLICY    APPLICANT   INSURED
─────────────┼────────────────────────────────────────
ORDER        │   ✓        ✓          ✓           ✓
POLICY       │   -        ✓          ✓           ✓
APPLICANT    │   -        -          ✓           -
INSURED      │   -        -          -           ✓
```

规则：只能向下存储（相同或更窄的范围），禁止向上写入。例如 POLICY 聚合的特征不能存到 ORDER 层。

### 关键路由路径

**ORDER × INSURED（最复杂路径）**：
1. Handler 返回 `{insuredId → {featureCode: value}}`
2. 通过 `findInsuredCtx(insuredId, featureCode)` 查询 `FeatureTargeting.featureInsuredPolicyMap`
3. 精确写入该被保人被标记的保单，不扩散到该被保人的其他保单

**ORDER × POLICY / APPLICANT 的过滤**：
- `findPolicyCtx(policyId, featureCode)` 内部检查 `featurePolicyTargetMap`
- 如果该保单在该特征的映射中不存在，返回 null，跳过写入

## 结果输出结构

`FeatureExtractionResult` 是扁平化的、完全可序列化的输出：

```
{
  "orderFeatures": {                              // 订单级特征
    "channel": "ONLINE"
  },
  "policyFeatures": {                             // 保单级特征
    "POL001": { "premium": 5000 },
    "POL002": { "premium": 3000 }
  },
  "insuredFeatures": {                            // 被保人特征
    "POL001": {
      "INS001": { "age": 35, "creditScore": 85 }
    }
  },
  "applicantFeatures": {                          // 投保人特征
    "POL001": {
      "APP001": { "age": 40 }
    }
  }
}
```

- 按保单 **严格隔离**：`insuredFeatures[policyId][insuredId]` 和 `applicantFeatures[policyId][applicantId]`
- 规则评估时按 `policyId + entityId` 精确查找，合并 ORDER → POLICY → APPLICANT/INSURED 的逐层覆盖

## 远程调用（SDK）

上游服务通过 `underwriting-feature-sdk` 的 Feign 客户端远程调用：

```java
@FeignClient(name = "underwriting-service", path = "/api/feature")
public interface FeatureExtractionClient {
    @PostMapping("/extract")
    FeatureExtractionResult extract(@RequestBody FeatureExtractionRequest request);
}
```

上游使用方式：
```java
@EnableFeignClients(basePackages = "com.insurance.uw.sdk.feature")
@SpringBootApplication
public class UpstreamApplication {
    // 注入 FeatureExtractionClient，调用 extract()
}
```

## 模块结构

```
underwriting-system/
├── underwriting-feature-sdk/       ← DTOs + Feign 客户端，供上游服务依赖
├── underwriting-domain/            ← 领域模型（Order, FeatureConfig, 上下文树）
├── underwriting-application/       ← 核心实现
│   └── feature/
│       ├── handler/                ← 5 种 CalcType 的 Handler 实现
│       ├── impl/                   ← FeatureExtractionServiceImpl（核心调度器）
│       └── routing/                ← FeatureResultDispatcher（结果路由）
├── underwriting-interfaces/        ← REST 控制器层
├── underwriting-infrastructure/    ← 基础设施（MyBatis、Redis 等）
└── underwriting-bootstrap/         ← Spring Boot 启动 & Bean 装配
```
