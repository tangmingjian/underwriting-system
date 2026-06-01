# 特征配置指南

本文档详细说明如何在数据库中配置各种类型的特征，涵盖所有计算类型、聚合级别、存储级别的组合场景。

---

## 目录

1. [表结构速览](#1-表结构速览)
2. [枚举值参考](#2-枚举值参考)
3. [CalcType 详解](#3-calctype-详解)
   - [3.1 PARAM_MAPPING — 入参取数](#31-param_mapping--入参取数)
   - [3.2 EXTERNAL_API — 外部接口调用](#32-external_api--外部接口调用)
   - [3.3 EXPRESSION / DATABASE_QUERY / COMPOSITE — 暂未实现](#33-expression--database_query--composite--暂未实现)
4. [聚合级别 × 存储级别 组合矩阵](#4-聚合级别--存储级别-组合矩阵)
5. [特征依赖配置](#5-特征依赖配置)
6. [Groovy 脚本编写指南](#6-groovy-脚本编写指南)
7. [规则配置](#7-规则配置)
8. [完整场景示例速查](#8-完整场景示例速查)

---

## 1. 表结构速览

### t_feature_config（特征配置表）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `feature_code` | VARCHAR(50) | 是 | 特征码，唯一标识，推荐命名规范见下方 |
| `feature_name` | VARCHAR(100) | 否 | 特征名称 |
| `category` | ENUM | 是 | `ATOMIC` / `DERIVED` / `COMPOSITE` |
| `data_type` | ENUM | 是 | `INT` / `DECIMAL` / `STRING` / `BOOLEAN` / `JSON` / `ARRAY` |
| `calc_type` | ENUM | 是 | 计算类型，决定 `calc_config` 的结构 |
| `calc_config` | JSON | 是 | 计算配置，**不同 calc_type 对应不同 JSON 结构** |
| `aggregation` | VARCHAR(20) | 是 | 聚合级别，决定特征在哪个维度执行 |
| `storage_level` | VARCHAR(20) | 是 | 存储级别，决定特征结果存在哪个上下文 |
| `depends_on` | JSON | 否 | 依赖的特征码列表，如 `["BASE_RISK"]` |
| `version` | INT | 否 | 版本号，默认 1 |
| `ttl_seconds` | INT | 否 | 缓存 TTL，-1 表示永不过期（入参取数类） |
| `source_system` | VARCHAR(64) | 否 | 数据来源系统 |
| `owner` | VARCHAR(64) | 否 | 负责人 |
| `status` | ENUM | 否 | `DRAFT` / `ACTIVE` / `DEPRECATED` |
| `extra_params` | VARCHAR(500) | 否 | 扩展参数 |

**特征码命名规范（推荐）**：

| 前缀 | 含义 | 示例 |
|---|---|---|
| `ins.` | 被保人级特征 | `ins.age`, `ins.creditScore` |
| `app.` | 投保人级特征 | `app.incomeVerified` |
| `pol.` | 保单级特征 | `pol.maxSumAssured` |
| `ord.` | 订单级特征 | `ord.channel`, `ord.fraudRiskScore` |
| 无前缀 | 通用/批量特征 | `BASE_RISK`, `RISK_SCORE` |

### t_feature_script（脚本表）

| 字段 | 说明 |
|---|---|
| `script_id` | 脚本标识，被 `calc_config` 中的 `input_script_id` / `output_script_id` 引用 |
| `script_type` | `INPUT`（入参映射，方法签名 `Map buildRequest(ctx)`） / `OUTPUT`（出参映射，方法签名 `Map extractFeatures(response, ctx)`） |
| `script_text` | Groovy 脚本全文 |
| `version` | 版本号，同一 `script_id` 可有多版本 |

### t_underwriting_rule（核保规则表）

| 字段 | 说明 |
|---|---|
| `rule_type` | `INSURED` / `APPLICANT` / `POLICY` / `ORDER` |
| `expression` | SpEL 表达式，如 `#root['ins.age'] >= 18` |
| `feature_codes` | 逗号分隔的特征码，如 `ins.age,ins.creditScore` |

---

## 2. 枚举值参考

### CalcType（计算类型）

| 值 | 说明 | calc_config 结构 | 状态 |
|---|---|---|---|
| `PARAM_MAPPING` | 从请求实体直接取字段值 | `{"source":"{entityType}.{fieldName}"}` | 已实现 |
| `EXTERNAL_API` | 调用下游 HTTP 接口 | `{"service":{...},"input_script_id":"...","output_script_id":"..."}` | 已实现 |
| `EXPRESSION` | SpEL 表达式计算 | — | 桩（未实现） |
| `DATABASE_QUERY` | 直接查库 | — | 桩（未实现） |
| `COMPOSITE` | 组合多个子特征 | — | 桩（未实现） |

### AggregationLevel（聚合级别）

| 值 | 调度行为 | Handler 收到的 ctx 类型 |
|---|---|---|
| `ORDER` | 整个订单执行 1 次 | `OrderFeatureContext` |
| `POLICY` | 每个保单执行 1 次 | `PolicyFeatureContext` |
| `APPLICANT` | 每个投保人执行 1 次 | `ApplicantFeatureContext` |
| `INSURED` | 每个被保人执行 1 次 | `InsuredFeatureContext` |

### StorageLevel（存储级别）

| 值 | 存储位置 | 谁可以读取 |
|---|---|---|
| `ORDER` | `OrderFeatureContext.orderFeatures` | 所有下级（POLICY / APPLICANT / INSURED） |
| `POLICY` | `PolicyFeatureContext.policyFeatures` | 该保单的 APPLICANT / INSURED |
| `APPLICANT` | `ApplicantFeatureContext.features` | 该投保人的规则 |
| `INSURED` | `InsuredFeatureContext.acquiredFeatures` | 该被保人的规则 |

### 其他枚举

| 枚举 | 可选值 |
|---|---|
| `FeatureCategory` | `ATOMIC`（原子特征，直接取值）、`DERIVED`（衍生特征）、`COMPOSITE`（复合特征） |
| `DataType` | `INT`、`DECIMAL`、`STRING`、`BOOLEAN`、`JSON`、`ARRAY` |
| `FeatureStatus` | `DRAFT`（草稿）、`ACTIVE`（启用）、`DEPRECATED`（废弃） |
| `RuleType` | `INSURED`、`APPLICANT`、`POLICY`、`ORDER` |

---

## 3. CalcType 详解

### 3.1 PARAM_MAPPING — 入参取数

**用途**：直接从请求实体（Order / Policy / Insured / Applicant）的字段中读取特征值，无需调用下游接口。

**calc_config 格式**：

```json
{"source": "{entityType}.{fieldName}"}
```

**entityType 支持的值**：

| entityType | 取数对象 | 说明 |
|---|---|---|
| `order` | `Order` 实体 | 订单号、渠道等 |
| `policy` | `Policy` 实体 | 保单号、产品码、投保金额等 |
| `insured` | `Insured` 实体 | 被保人年龄、性别、职业等 |
| `applicant` | `Applicant` 实体 | 投保人年龄、性别等 |
| `feature` | 已计算的依赖特征 | 从已有特征中提取子字段 |

**source 支持嵌套路径**：`insured.age`、`policy.product.productCode`、`feature.BASE_RISK.riskScore`

#### 场景 A：取被保人字段（最常用）

```sql
-- 聚合级别 POLICY + 存储级别 INSURED：对每个保单遍历所有被保人，每人存自己的特征
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ins.age', '被保人年龄', 'ATOMIC', 'INT', 'PARAM_MAPPING',
    '{"source":"insured.age"}',
    'POLICY', 'INSURED', 1, -1, 'request', 'admin', 'DRAFT'
);
```

#### 场景 B：取订单级字段

```sql
-- 聚合级别 ORDER + 存储级别 ORDER：整个订单执行一次，存订单级
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ord.channel', '渠道编码', 'ATOMIC', 'STRING', 'PARAM_MAPPING',
    '{"source":"order.channel"}',
    'ORDER', 'ORDER', 1, -1, 'request', 'admin', 'DRAFT'
);
```

#### 场景 C：取保单级字段（如投保金额）

```sql
-- 聚合 ORDER + 存储 POLICY：整个订单执行一次，遍历所有保单分别存储
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'pol.appliedAmount', '投保金额', 'ATOMIC', 'DECIMAL', 'PARAM_MAPPING',
    '{"source":"policy.appliedAmount"}',
    'ORDER', 'POLICY', 1, -1, 'request', 'admin', 'DRAFT'
);
```

#### 场景 D：从依赖特征提取子字段（feature 类型）

```sql
-- 先有一个 BASE_RISK（EXTERNAL_API）特征，返回 { riskScore: 85, fraudScore: 20 }
-- 然后分别拆出两个 PARAM_MAPPING 特征

-- 子特征 1：从 BASE_RISK 取 riskScore
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level, depends_on,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'RISK_SCORE', '风险评分', 'ATOMIC', 'INT', 'PARAM_MAPPING',
    '{"source":"feature.BASE_RISK.riskScore"}',
    'ORDER', 'INSURED', '["BASE_RISK"]',
    1, -1, 'request', 'admin', 'DRAFT'
);

-- 子特征 2：从 BASE_RISK 取 fraudScore
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level, depends_on,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'FRAUD_CHECK', '反欺诈检查', 'ATOMIC', 'INT', 'PARAM_MAPPING',
    '{"source":"feature.BASE_RISK.fraudScore"}',
    'ORDER', 'INSURED', '["BASE_RISK"]',
    1, -1, 'request', 'admin', 'DRAFT'
);
```

> **依赖关系**：`depends_on` 确保 BASE_RISK 先于 RISK_SCORE / FRAUD_CHECK 执行。

**PARAM_MAPPING 完整 source 示例**：

| source | 含义 |
|---|---|
| `insured.age` | 被保人年龄 |
| `insured.gender` | 被保人性别 |
| `insured.occupation` | 被保人职业 |
| `insured.idNo` | 被保人身份证号 |
| `insured.name` | 被保人姓名 |
| `applicant.age` | 投保人年龄 |
| `applicant.idNo` | 投保人身份证号 |
| `policy.appliedAmount` | 投保金额 |
| `policy.product.productCode` | 产品代码（嵌套路径） |
| `order.channel` | 渠道 |
| `order.orderId` | 订单号 |
| `feature.BASE_RISK.riskScore` | 从已有特征取子字段 |

---

### 3.2 EXTERNAL_API — 外部接口调用

**用途**：调用下游 HTTP 接口获取特征值。

**必需配套**：需要在 `t_feature_script` 表中录入两个 Groovy 脚本（入参映射 + 出参提取）。

**calc_config 格式**：

```json
{
  "service": {
    "discovery_type": "NACOS | STATIC | DIRECT",
    "service_name": "服务名（NACOS 时必填）",
    "namespace": "命名空间（NACOS）",
    "group": "分组（NACOS）",
    "static_endpoints": ["端点列表（STATIC 时必填）"],
    "protocol": "HTTP | HTTPS",
    "path": "接口路径",
    "method": "GET | POST",
    "timeout_ms": 超时毫秒数,
    "headers": { "Header-Name": "value" }
  },
  "input_script_id": "入参脚本标识",
  "output_script_id": "出参脚本标识"
}
```

**三种服务发现模式**：

| discovery_type | 说明 | 关键字段 |
|---|---|---|
| `NACOS` | 通过 Nacos 注册中心发现 | `service_name`, `namespace`, `group` |
| `STATIC` | 静态配置多个端点（支持故障转移） | `static_endpoints` |
| `DIRECT` | 直连 URL | `path` 填完整 URL |

**批处理**：同一 `AggregationLevel` + 同一 `service_key`（由 discovery_type + path 决定）的多个 EXTERNAL_API 特征，调度器会自动合并为一次 HTTP 调用（`executeBatch`），各特征的 Groovy 脚本分别提取各自的字段。

#### 场景 E：ORDER 聚合 + INSURED 存储（订单下批量取数，结果存到各被保人）

```sql
-- 1. 先录入脚本
INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('buildCreditScoreReq', '信用评分-入参', 'INPUT',
'Map buildRequest(OrderFeatureContext ctx) {
    List<Map> persons = []
    ctx.getInsuredsForFeature("ins.creditScore").each { insCtx ->
        persons << [
            customerNos : insCtx.customerNos ?: [insCtx.insuredId],
            name        : insCtx.name,
            idNo        : insCtx.idNo,
            refId       : insCtx.insuredId
        ]
    }
    return [
        orderNo    : ctx.orderId,
        channelCode: ctx.channel ?: "ONLINE",
        queryType  : "CREDIT_SCORE",
        persons    : persons
    ]
}', 1, 'ACTIVE');

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('extractCreditScore', '信用评分-出参', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, OrderFeatureContext ctx) {
    Map<String, Map<String, Object>> result = [:]
    List scores = response?.data?.scores ?: []
    scores.each { item ->
        String refId = item.refId as String
        result[refId] = [
            "score": (item.score ?: 0) as int,
            "level": item.level ?: "C"
        ]
    }
    return result
}', 1, 'ACTIVE');

-- 2. 再录入特征配置
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ins.creditScore', '信用评分', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"STATIC","static_endpoints":["https://api.credit-center.gov.cn","https://api2.credit-center.gov.cn"],"protocol":"HTTPS","path":"/v2/credit/score","method":"POST","timeout_ms":5000,"headers":{"X-Api-Key":"${env.CREDIT_API_KEY}"}},"input_script_id":"buildCreditScoreReq","output_script_id":"extractCreditScore"}',
    'ORDER', 'INSURED', 1, 300, 'credit-center', 'zhangsan', 'DRAFT'
);
```

**执行流程**：
1. 调度器发现 `aggregation=ORDER`，将 `OrderFeatureContext` 传给 Handler
2. 执行 `buildRequest(ctx)` → 遍历 `ctx.getInsuredsForFeature()` 收集所有被保人的 customerNos → 拼装请求体
3. HTTP 调用下游接口
4. 执行 `extractFeatures(response, ctx)` → 按 refId 反查归属 → 返回 `{ INS001: {score: 680, level: "A"}, INS002: {...} }`
5. Handler 用 `featureCode` 包裹后，按 `storage_level=INSURED` 存入各被保人上下文

#### 场景 F：ORDER 聚合 + ORDER 存储（订单级特征，如反欺诈评分）

```sql
INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('buildFraudRiskReq', '反欺诈-入参', 'INPUT',
'Map buildRequest(OrderFeatureContext ctx) {
    List<Map> persons = []
    ctx.getInsuredsForFeature("ord.fraudRiskScore").each { insCtx ->
        persons << [
            customerNos: insCtx.customerNos ?: [insCtx.insuredId],
            name       : insCtx.name,
            idNo       : insCtx.idNo
        ]
    }
    return [
        orderNo  : ctx.orderId,
        channel  : ctx.channel,
        persons  : persons
    ]
}', 1, 'ACTIVE');

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('extractFraudRisk', '反欺诈-出参', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, OrderFeatureContext ctx) {
    int riskScore = (response?.data?.riskScore ?: 0) as int
    String riskLevel = response?.data?.riskLevel ?: "LOW"
    return ["__ORDER__": [
        "riskScore": riskScore,
        "riskLevel": riskLevel
    ]]
}', 1, 'ACTIVE');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ord.fraudRiskScore', '反欺诈风险评分', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"NACOS","service_name":"risk-control-svc","namespace":"production","group":"DEFAULT_GROUP","protocol":"HTTP","path":"/api/v1/fraud/score","method":"POST","timeout_ms":2000},"input_script_id":"buildFraudRiskReq","output_script_id":"extractFraudRisk"}',
    'ORDER', 'ORDER', 1, 600, 'risk-control', 'lisi', 'DRAFT'
);
```

> **注意**：`storage_level=ORDER` 时，出参脚本的 key 应为 `__ORDER__`。

#### 场景 G：POLICY 聚合 + INSURED 存储（每保单批量取被保人数据）

```sql
INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('buildOccupationRiskReq', '职业风险-入参', 'INPUT',
'Map buildRequest(PolicyFeatureContext polCtx) {
    List<Map> insureds = polCtx.insureds.collect { insCtx ->
        [
            insuredId   : insCtx.insuredId,
            customerNos : insCtx.customerNos ?: [insCtx.insuredId],
            occupation  : insCtx.occupation ?: "UNKNOWN"
        ]
    }
    return [
        policyNo   : polCtx.policyId,
        productCode: polCtx.productCode,
        insureds   : insureds
    ]
}', 1, 'ACTIVE');

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('extractOccupationRisk', '职业风险-出参', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, PolicyFeatureContext polCtx) {
    Map<String, Map<String, Object>> result = [:]
    List risks = response?.data?.occupationRisks ?: []
    risks.each { item ->
        String refId = item.refId as String
        result[refId] = [
            "riskClass": (item.riskClass ?: 1) as int,
            "riskDesc" : item.riskDescription ?: ""
        ]
    }
    return result
}', 1, 'ACTIVE');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ins.occupationRisk', '职业风险等级', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"DIRECT","path":"https://occupation-risk.internal.api.com/v1/risk/evaluate","protocol":"HTTPS","method":"POST","timeout_ms":3000},"input_script_id":"buildOccupationRiskReq","output_script_id":"extractOccupationRisk"}',
    'POLICY', 'INSURED', 1, 1800, 'occupation-risk', 'wangwu', 'DRAFT'
);
```

> **POLICY 级 ctx**：入参脚本收到的 ctx 是 `PolicyFeatureContext`，可通过 `polCtx.insureds`、`polCtx.applicantCtx`、`polCtx.orderContext` 导航。

#### 场景 H：INSURED 聚合 + INSURED 存储（每个被保人独立调接口）

```sql
-- 入参脚本收到的 ctx 是 InsuredFeatureContext
INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('buildHealthScoreReq', '健康评分-入参', 'INPUT',
'Map buildRequest(InsuredFeatureContext insCtx) {
    return [
        customerNos: insCtx.customerNos ?: [insCtx.insuredId],
        idNo       : insCtx.idNo,
        name       : insCtx.name,
        age        : insCtx.age,
        gender     : insCtx.gender
    ]
}', 1, 'ACTIVE');

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status) VALUES
('extractHealthScore', '健康评分-出参', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, InsuredFeatureContext insCtx) {
    int score = (response?.data?.healthScore ?: 0) as int
    String grade = response?.data?.healthGrade ?: "STANDARD"
    return [(insCtx.insuredId): [
        "healthScore": score,
        "healthGrade": grade
    ]]
}', 1, 'ACTIVE');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ins.healthScore', '健康评分', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"DIRECT","path":"https://health-api.internal.com/v1/score","protocol":"HTTPS","method":"POST","timeout_ms":2000},"input_script_id":"buildHealthScoreReq","output_script_id":"extractHealthScore"}',
    'INSURED', 'INSURED', 1, 600, 'health-engine', 'admin', 'DRAFT'
);
```

> **INSURED 级**：每个被保人独立获得一个 `CompletableFuture`，并行执行。ctx 是 `InsuredFeatureContext`，可通过 `insCtx.policyContext`、`insCtx.orderContext` 向上导航。

---

### 3.3 EXPRESSION / DATABASE_QUERY / COMPOSITE — 暂未实现

这三个类型目前是桩实现，调用会抛出 `UnsupportedOperationException`。待后续实现。

---

## 4. 聚合级别 × 存储级别 组合矩阵

下表列出所有合理的 AggregationLevel × StorageLevel 组合及典型场景：

| 聚合级别 | 存储级别 | 典型场景 | 说明 |
|---|---|---|---|
| `ORDER` | `ORDER` | 订单反欺诈评分、订单总风险等级 | 全订单一次查询，结果存订单 |
| `ORDER` | `POLICY` | 从订单维度取各保单投保金额 | 遍历各保单分别存储 |
| `ORDER` | `APPLICANT` | 订单级查询投保人数据 | 遍历各投保人分别存储 |
| `ORDER` | `INSURED` | **信用评分（最常用）**：订单下批量查所有被保人 | 一次 HTTP，结果按人分发 |
| `POLICY` | `POLICY` | 产品保额上限查询 | 每保单一次查询 |
| `POLICY` | `APPLICANT` | 投保人收入核验 | 每保单查投保人 |
| `POLICY` | `INSURED` | **职业风险、入参取数**：保单下遍历被保人 | 每保单一次（或每保单每被保人） |
| `INSURED` | `INSURED` | **健康评分**：每人独立调单被保人接口 | 每个被保人独立 Future 并行 |
| `APPLICANT` | `APPLICANT` | 投保人独立征信查询 | 每个投保人独立并行 |

**选择原则**：
- **聚合级别**取决于"一次请求能覆盖多少人"：
  - 一次请求能查全订单所有人 → `ORDER`
  - 一次请求只能查一个保单下的人 → `POLICY`
  - 一次请求只能查一个人 → `INSURED` / `APPLICANT`
- **存储级别**取决于"结果给谁用"：
  - 订单级规则用 → `ORDER`
  - 被保人级规则用 → `INSURED`
  - 跨级规则也可以通过 `FeatureCollector` 的合并策略读取上级特征

---

## 5. 特征依赖配置

### depends_on 字段

值为 JSON 数组，如 `["BASE_RISK"]` 或 `["ins.age", "ord.channel"]`。

**核心规则**：

1. **依赖先执行**：通过 Kahn 拓扑排序确保依赖特征先于当前特征执行
2. **同级可并行**：无依赖关系的特征在同一层内并行执行
3. **只能依赖上级或同级**：
   - ORDER 特征可依赖 ORDER 特征 ✓
   - POLICY 特征可依赖 ORDER 或 POLICY 特征 ✓
   - INSURED 特征可依赖 ORDER / POLICY / INSURED 特征 ✓
   - ORDER 特征**不能**依赖 INSURED 特征 ✗（会被校验拦截）
4. **传递依赖自动展开**：请求特征 B（依赖 A），系统自动将 A 也纳入执行计划

**依赖方向校验**：

```
ORDER(3) > POLICY(2) > APPLICANT(1) > INSURED(0)

允许：featureLevel.rank <= dependencyLevel.rank  （依赖方层级 ≤ 被依赖方层级）
禁止：featureLevel.rank > dependencyLevel.rank   （上级依赖下级）
```

### 典型依赖场景

**场景 1：批量查询 + 拆分子特征**

```
BASE_RISK (EXTERNAL_API, ORDER, INSURED)  ← 批量查询，结果包含 riskScore + fraudScore
    ↑ depends_on
RISK_SCORE (PARAM_MAPPING, ORDER, INSURED)   ← 从 BASE_RISK 提取 riskScore
FRAUD_CHECK (PARAM_MAPPING, ORDER, INSURED)  ← 从 BASE_RISK 提取 fraudScore
```

执行顺序：Layer 1: BASE_RISK → Layer 2: RISK_SCORE, FRAUD_CHECK（并行）

**场景 2：链式依赖**

```
ins.age (PARAM_MAPPING) → ins.healthScore (EXTERNAL_API, depends_on=[ins.age])
                                         → ins.riskLevel (EXPRESSION, depends_on=[ins.healthScore])
```

**SQL 示例**（场景 1）：

```sql
-- 批量查询基础风险（父特征）
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level, depends_on,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'BASE_RISK', '基础风险批量查询', 'ATOMIC', 'JSON', 'EXTERNAL_API',
    '{"service":{...},"input_script_id":"buildBaseRiskReq","output_script_id":"extractBaseRisk"}',
    'ORDER', 'INSURED', '[]',
    1, 300, 'risk-engine', 'admin', 'DRAFT'
);

-- 从 BASE_RISK 提取 riskScore（子特征)
INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level, depends_on,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'RISK_SCORE', '风险评分', 'ATOMIC', 'INT', 'PARAM_MAPPING',
    '{"source":"feature.BASE_RISK.riskScore"}',
    'ORDER', 'INSURED', '["BASE_RISK"]',
    1, -1, 'request', 'admin', 'DRAFT'
);
```

---

## 6. Groovy 脚本编写指南

### 6.1 入参脚本（INPUT）

**方法签名**：`Map buildRequest({ContextType} ctx)`

ContextType 取决于特征的 `aggregation` 级别：

| aggregation | ctx 类型 | 可用属性/方法 |
|---|---|---|
| `ORDER` | `OrderFeatureContext` | `ctx.orderId`, `ctx.channel`, `ctx.policies`, `ctx.getInsuredsForFeature(fc)`, `ctx.getPoliciesForFeature(fc)` |
| `POLICY` | `PolicyFeatureContext` | `ctx.policyId`, `ctx.productCode`, `ctx.insureds`, `ctx.applicantCtx`, `ctx.orderContext` |
| `INSURED` | `InsuredFeatureContext` | `ctx.insuredId`, `ctx.name`, `ctx.age`, `ctx.gender`, `ctx.occupation`, `ctx.customerNos`, `ctx.policyContext`, `ctx.orderContext` |
| `APPLICANT` | `ApplicantFeatureContext` | `ctx.applicantId`, `ctx.name`, `ctx.age`, `ctx.gender`, `ctx.customerNos`, `ctx.policyContext`, `ctx.orderContext` |

**返回值**：拼装好的下游请求 Map。

### 6.2 出参脚本（OUTPUT）

**方法签名**：`Map<String, Map<String, Object>> extractFeatures(Map response, {ContextType} ctx)`

**返回值**：`{ targetId: { field1: val1, field2: val2 }, ... }`

- `targetId` 是被保人 ID / 投保人 ID / 保单 ID / `__ORDER__`
- value 是 `Map<String, Object>`，用**语义化字段名**（不要用 featureCode 作为 key）
- Handler 会自动用 `featureCode` 包裹：`{ targetId: { featureCode: { field1: val1 } } }`

### 6.3 关键上下文方法

**OrderFeatureContext**：

| 方法 | 说明 |
|---|---|
| `ctx.orderId` | 订单号 |
| `ctx.channel` | 渠道 |
| `ctx.orderFeatures` | 已计算的订单级特征 Map |
| `ctx.policies` | 所有保单上下文列表 |
| `ctx.findInsuredCtx(insuredId)` | 按 ID 查被保人上下文 |
| `ctx.findPolicyCtx(policyId)` | 按 ID 查保单上下文 |
| `ctx.getAllInsuredContexts()` | 获取所有被保人上下文（扁平化） |
| `ctx.getInsuredsForFeature(featureCode)` | 获取该特征相关产品下的被保人（推荐用此方法） |
| `ctx.getPoliciesForFeature(featureCode)` | 获取该特征相关产品下的保单 |

**PolicyFeatureContext**：

| 方法 | 说明 |
|---|---|
| `ctx.policyId` | 保单号 |
| `ctx.productCode` | 产品代码 |
| `ctx.insureds` | 该保单下所有被保人上下文 |
| `ctx.applicantCtx` | 投保人上下文 |
| `ctx.policyFeatures` | 已计算的保单级特征 |
| `ctx.orderContext` | 向上导航到订单上下文 |

**InsuredFeatureContext**：

| 方法 | 说明 |
|---|---|
| `ctx.insuredId` | 被保人 ID |
| `ctx.name` / `ctx.age` / `ctx.gender` | 代理属性 |
| `ctx.occupation` / `ctx.phone` | 代理属性 |
| `ctx.customerNos` | 同人客户号列表 |
| `ctx.acquiredFeatures` | 已获取的特征 |
| `ctx.policyContext` | 向上导航到保单 |
| `ctx.orderContext` | 向上导航到订单 |

**ApplicantFeatureContext**：

| 方法 | 说明 |
|---|---|
| `ctx.applicantId` | 投保人 ID |
| `ctx.name` / `ctx.age` / `ctx.gender` | 代理属性 |
| `ctx.customerNos` | 同人客户号列表 |
| `ctx.features` | 已获取的特征 |
| `ctx.policyContext` | 向上导航到保单 |
| `ctx.orderContext` | 向上导航到订单 |

### 6.4 同人客户号模式（推荐）

**背景**：同一个自然人在不同系统中可能有多个客户号（如历史保单号、渠道号等）。调用下游时携带全部客户号可以更全面地查询。

**模式**：

1. `buildRequest`：从 `ctx.customerNos` 获取全部客户号，放入请求
2. `extractFeatures`：下游返回按 `customerNo` 粒度的结果 → 用 `ctx.customerNos.contains(custNo)` 反查归属 → 合并同人多个结果

**合并策略示例**：

```groovy
// 评分类：取最高分
if (score > existingScore) { result[refId] = ["score": score] }

// 风险类：取最高风险等级
if (riskClass > existClass) { result[refId] = ["riskClass": riskClass] }

// 验证类：全部通过才算通过
allVerified = allVerified && verified
```

---

## 7. 规则配置

### 规则表 SQL

```sql
INSERT INTO t_underwriting_rule (
    rule_code, rule_name, rule_type, expression,
    feature_codes, product_code, priority, status
) VALUES (
    'RULE_AGE_001', '被保人成年检查', 'INSURED',     -- rule_type: INSURED/APPLICANT/POLICY/ORDER
    '#root[''ins.age''] >= 18',                      -- SpEL 表达式（注意单引号转义）
    'ins.age',                                        -- 依赖的特征码（逗号分隔）
    'HEALTH_A_001', 10, 1                             -- 适用产品(null=全部), 优先级, 启用
);
```

### RuleType 与 SpEL 表达式

| RuleType | 评估次数 | 特征收集范围 | 表达式示例 |
|---|---|---|---|
| `INSURED` | 每个被保人 1 次 | ORDER + POLICY + APPLICANT + INSURED | `#root['ins.age'] >= 18` |
| `APPLICANT` | 每个投保人 1 次 | ORDER + POLICY + APPLICANT | `#root['app.incomeVerified']['incomeVerified'] == true` |
| `POLICY` | 每个保单 1 次 | ORDER + POLICY | `#root['pol.maxSumAssured']['maxSumAssured'] > 0` |
| `ORDER` | 整个订单 1 次 | ORDER | `#root['ord.fraudRiskScore']['riskScore'] < 80` |

### SpEL 访问特征值的方式

```
# 简单值（PARAM_MAPPING 直接取值）
#root['ins.age'] >= 18

# 语义 Map 的子字段（EXTERNAL_API 返回的是 Map）
#root['ins.creditScore']['score'] >= 600
#root['ord.fraudRiskScore']['riskScore'] < 80

# 复杂表达式
#root['ins.age'] > 30 and #root['RISK_SCORE'] > 60
```

> **注意**：SpEL 中单引号在 SQL 里需要转义为 `''`（两个单引号）。

**特征收集覆盖规则**：低层特征覆盖高层同名特征。例如 INSURED 规则收集时：`ORDER → POLICY → APPLICANT → INSURED`，后 put 的覆盖前面的。

---

## 8. 完整场景示例速查

| # | 场景 | calc_type | aggregation | storage_level | depends_on | 关键配置要点 |
|---|---|---|---|---|---|---|
| 1 | 被保人年龄/性别/职业 | `PARAM_MAPPING` | `POLICY` | `INSURED` | 无 | `{"source":"insured.age"}` |
| 2 | 订单渠道 | `PARAM_MAPPING` | `ORDER` | `ORDER` | 无 | `{"source":"order.channel"}` |
| 3 | 投保金额 | `PARAM_MAPPING` | `ORDER` | `POLICY` | 无 | `{"source":"policy.appliedAmount"}` |
| 4 | 从依赖特征提取子字段 | `PARAM_MAPPING` | `ORDER` | `INSURED` | `["BASE_RISK"]` | `{"source":"feature.BASE_RISK.riskScore"}` |
| 5 | 信用评分（订单聚合批量查） | `EXTERNAL_API` | `ORDER` | `INSURED` | 无 | STATIC 多端点，Groovy 按 customerNo 反查 |
| 6 | 反欺诈评分（订单级特征） | `EXTERNAL_API` | `ORDER` | `ORDER` | 无 | NACOS 发现，出参 key=`__ORDER__` |
| 7 | 职业风险（保单聚合） | `EXTERNAL_API` | `POLICY` | `INSURED` | 无 | DIRECT 直连，每保单一次 HTTP |
| 8 | 投保人收入核验 | `EXTERNAL_API` | `POLICY` | `APPLICANT` | 无 | STATIC，ctx 是 PolicyFeatureContext |
| 9 | 产品保额上限 | `EXTERNAL_API` | `POLICY` | `POLICY` | 无 | 纯保单级，不涉及被保人 |
| 10 | 健康评分（每个被保人独立） | `EXTERNAL_API` | `INSURED` | `INSURED` | 无 | 每人独立 Future，ctx 是 InsuredFeatureContext |
| 11 | 批量查询 + 拆分子特征 | 父:`EXTERNAL_API`<br>子:`PARAM_MAPPING` | 父:`ORDER`<br>子:`ORDER` | 父:`INSURED`<br>子:`INSURED` | 子 depends_on 父 | 一次 HTTP 调用，子特征用 `feature.` 前缀提取 |

---

## 附录：数据库完整示例（init_data.sql）

项目 `underwriting-bootstrap/src/main/resources/init_data.sql` 包含了 11 个场景的完整 SQL 示例，涵盖所有服务发现模式 × 聚合级别 × 存储级别的组合，以及对应的核保规则配置。可以直接参考该文件中的 INSERT 语句。
