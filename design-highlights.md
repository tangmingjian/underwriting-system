# 核保配置化系统 — 设计亮点

> 面向团队内部分享：系统的核心设计亮点、灵活性体现、生产适应性分析。

---

## 一、特征引擎 — 6 种计算策略可插拔

### 1.1 策略模式：一种 CalcType 对应一个 Handler

每种计算类型对应一个 `FeatureCalcHandler` 实现，Spring 通过 `List<FeatureCalcHandler>` 自动注入，按 `getSupportedType()` 注册为 Map。

| CalcType | Handler | 用途 |
|----------|---------|------|
| `PARAM_MAPPING` | ParamMappingCalcHandler | 直接从请求实体字段取值，零网络开销 |
| `EXTERNAL_API` | ExternalApiCalcHandler | 调用下游 HTTP，Groovy 脚本拼装请求 / 解析响应 |
| `EXPRESSION` | ExpressionCalcHandler | 本地 Groovy 表达式计算（如年龄 = Period.between(生日, 生效日)） |
| `CUSTOM` | CustomCalcHandler | Java 类实现，支持任意复杂逻辑，通过 `@Component` 自动发现 |
| `DATABASE_QUERY` | 桩 | 预留 |
| `COMPOSITE` | 桩 | 预留 |

**灵活体现**：新增计算方式只需 3 步——加枚举值、写 Handler、注册 Bean。核心流水线代码零改动。

### 1.2 4 级聚合 × 4 级存储 = 16 格路由矩阵

`AggregationLevel`（在哪层执行）× `StorageLevel`（结果存到哪层），通过 `FeatureResultDispatcher` 做矩阵路由。

```
agg \ storage | ORDER | POLICY | APPLICANT | INSURED
--------------+-------+--------+-----------+--------
ORDER         |  ✓    |   ✓    |    ✓      |   ✓
POLICY        |  -    |   ✓    |    ✓      |   ✓
APPLICANT     |  -    |   -    |    ✓      |   -
INSURED       |  -    |   -    |    -      |   ✓
```

"-" = 拒绝向上路由（聚合层比存储层窄，语义不合理），日志 warn。

**灵活体现**：信用评分 `ins.creditScore`，aggregation=ORDER、storage=INSURED。一次调下游 API，结果通过 `FeatureTargeting` 精确写入每个被保人上下文（跨保单隔离），避免每个被保人调一次。

### 1.3 拓扑排序 + 分层并发执行

Kahn 算法将特征按 `dependsOn` 依赖关系分层，层间串行、层内跨 Aggregation 并发（线程池 `uw-feature-*`）。

```
A ──→ B ──→ D       Layer 0: {A, C}
C ──→ E              Layer 1: {B, E}
                     Layer 2: {D}
```

**灵活体现**：配置时只需声明 `dependsOn: ["BASE_RISK"]`，系统自动 BFS 加载依赖链、拓扑排序、按层执行。依赖特征不输出到最终结果，只在上下文里供下游解析。支持多级传递依赖。

### 1.4 FeatureTargeting — 精确到 (policyId, insuredId) 的特征-实体映射

上游调用方传入 `{POL001: {INS001: [creditScore], INS002: [creditScore]}}`，系统内部构建 3 个派生映射：

| 映射 | 用途 |
|------|------|
| `featureInsuredTargetMap` | 特征 → 哪些被保人需要 |
| `featurePolicyTargetMap` | 特征 → 哪些保单需要 |
| `featureInsuredPolicyMap` | 特征 → (被保人, 保单) 精确配对 |

依赖特征的 target 通过 `while (changed)` 迭代传播，支持多级依赖链自动继承。

**灵活体现**：同一被保人 INS001 出现在 POL001 和 POL002，各有一份独立上下文。`featureInsuredPolicyMap` 确保 POL001 要的 creditScore 不会错误写入 POL002 的 INS001。

### 1.5 同服务合并（deepMerge）

`ExternalApiCalcHandler.executeBatch()` 将同组特征合并为一次 HTTP 调用。

```
特征A buildRequest → {persons: [{refId: "INS001", name: "张三"}]}
特征B buildRequest → {persons: [{refId: "INS002", name: "李四"}]}
                          ↓ deepMerge
合并请求            → {persons: [{...INS001}, {...INS002}]}
                          ↓ 单次 HTTP 调用
响应 → extractFeatures_A → {INS001: {...}}
     → extractFeatures_B → {INS002: {...}}
```

**规则**：同 key 的 Map 递归合并、List 拼接、其余覆盖。

**灵活体现**：`BASE_RISK` 一次查询返回 `{riskScore, fraudScore, amlFlag}`，下游 `RISK_SCORE` 和 `FRAUD_CHECK` 各取子字段。省掉 N-1 次网络往返。

### 1.6 Groovy 脚本热加载

三种脚本类型，存 `t_feature_script` 表，版本管理：

| 类型 | 方法签名 | 用途 |
|------|---------|------|
| INPUT | `Map buildRequest(ctx)` | 拼装下游 API 请求 |
| OUTPUT | `Map extractFeatures(response, ctx)` | 解析下游 API 响应 |
| EXPRESSION | `Map evaluate(ctx)` | 本地表达式计算 |

Caffeine 编译缓存（200 条，访问后 1h 过期）。修改后双重驱逐：FC Redis 缓存驱逐前、后各清一次脚本缓存（Redis + Caffeine），收窄并发下的竞态窗口。

脚本自动 import `domain.model.entity.*` 和 `domain.context.*`，可直接写 `ctx.insured.birthday`、`ctx.policy.effectiveDate` 等。

**灵活体现**：修改下游映射逻辑或新增表达式，改数据库脚本就行，不需要发版。版本回滚通过 version 字段实现。

---

## 二、规则引擎 — 3 种评估策略可扩展

### 2.1 策略模式

`RuleEngineFactory` 根据规则的 `eval_type` 分派：

| EvalType | 评估器 | 描述 |
|----------|--------|------|
| `CONDITION_LIST` | ConditionListEvaluator | 嵌套 AND/OR 条件树，12 种运算符（EQ/NEQ/GT/GTE/LT/LTE/BETWEEN/IN/NOT_IN/CONTAINS/IS_NULL/IS_NOT_NULL） |
| `CROSS_DECISION_TABLE` | CrossDecisionTableEvaluator | 二维决策矩阵（行 × 列），支持 `*` 通配，defaultResult 兜底 |
| `SCORECARD` | ScorecardEvaluator | 多维评分 + 公式计算（四则运算）+ 分桶判定，内嵌递归下降表达式解析器 |

**灵活体现**：规则数据（条件、表格、评分卡）全部存 JSON，运行时修改即生效。新增评估类型只需加枚举 + 实现 `RuleEvaluator` + 在 Factory 注册。

### 2.2 产品级规则隔离

每条规则可绑定 `product_code`。评估时只匹配当前保单的产品码。

```
产品 A：信用评分 ≥600, 年龄 ≥18
产品 B：职业风险 ≤3, 收入核验通过
```

同一部署支持多产品线各自独立规则集。

### 2.3 话素（Wording）系统

每条规则的 `wording_config` 按端 × 场景 × 模板生成差异化话术：

```json
{
  "A": { "pass": "您的信用评分 {{creditScore.score}} 达标",
         "fail": "信用评分 {{creditScore.score}} 不足，需要 ≥600" },
  "B": { "pass": "风控通过，评分 {{creditScore.score}}",
         "fail": "风控拒绝 — {{creditScore.score}}＜600" }
}
```

`WordingResolver` 用正则 `\{\{(.+?)}}` 提取路径，从特征值 Map 递归取值替换。

**灵活体现**：A/B/C 三端（客户/核保/运营）可配置不同话术，支持嵌套路径 `{{ins.creditScore.score}}`。

### 2.4 完整审计日志

每次规则的 Create/Update/Delete 自动快照到 `t_underwriting_rule_history`。Update 在修改前保存旧状态，Delete 在删除前保存。按 rule_code 索引，支持完整变更追溯。

---

## 三、架构级亮点

### 3.1 DDD 分层

```
common → domain → application → interfaces → bootstrap
                         ↓            ↓
                    feature-sdk   infrastructure
```

- domain 层零基础设施依赖
- application 层不依赖 infrastructure
- bootstrap 层负责所有 Bean 组装
- feature-sdk 独立发布为 jar

### 3.2 SDK 独立发布

`underwriting-feature-sdk` 含 Feign 客户端 + DTO，上游引入依赖后直接 `@Autowired FeatureExtractionClient` 远程调用。通过 Nacos 服务发现。

### 3.3 两段缓存

| 层 | 技术 | Key 模式 | TTL |
|----|------|---------|-----|
| Redis | StringRedisTemplate | `uw:fc:` / `uw:rule:` / `uw:script:` / `uw:cdt:` / `uw:sc:` / `uw:result:` / `uw:rule:history:` | 按实体粒度可配 |
| Caffeine | 本地缓存 | scriptId → 编译类 | 200 条, 1h 过期 |

- 特征结果 TTL 来自 `t_feature_config.ttl_seconds`（每个特征独立配置，-1=永不过期）
- 缓存逐出用 SCAN 迭代（非 KEYS），生产安全

### 3.4 异步 + MDC 传播

非 PARAM_MAPPING 类型走 `CompletableFuture.runAsync`，MDC 上下文在异步边界 `try { MDC.setContextMap(...) } finally { MDC.clear() }` 显式传播，保证日志链路追踪不丢失。

### 3.5 零拷贝上下文树

`OrderFeatureContext → PolicyFeatureContext → InsuredFeatureContext / ApplicantFeatureContext` 只持有实体引用，不拷贝字段。双向引用可任意层级向上导航。同一被保人在不同保单中有独立上下文实例，通过 `(policyId, insuredId)` 隔离。

---

## 四、总结

| 生产需求 | 如何解决 |
|---------|---------|
| 不同产品不同规则 | productCode 过滤 |
| 减少下游 API 调用 | ORDER 聚合 + deepMerge 合并 |
| 特征间有依赖 | Kahn 拓扑排序 + dependsOn 声明式配置 |
| 逻辑变更免发版 | Groovy 脚本热加载 |
| 新增计算/评估方式 | 策略模式，加 Handler/Evaluator 即插即用 |
| 缓存一致性 | 双重驱逐 + SCAN 删除，收窄竞态窗口 |
| 日志追踪 | MDC 跨异步线程传播 |
| 上游集成 | SDK jar + Feign + Nacos |
| 决策可追溯 | 规则变更历史全量归档 |

**核心设计哲学：配置驱动、脚本灵活、策略可插拔、数据与逻辑分离。**

---

## 五、用了什么 · 解决了什么问题 · 达到了什么效果

### 5.1 总体概览

| 维度 | 说明 |
|------|------|
| **用了什么** | Spring Boot 3.3 + MyBatis-Plus + Redis + Caffeine + Groovy 4.0 + Feign + Nacos + CompletableFuture 异步线程池 + DDD 分层架构 + 策略模式 + 拓扑排序 + Jackson JSON DSL |
| **解决了什么问题** | 核保决策中特征取数来源多样（请求入参、下游 API、表达式计算、自定义 Java 逻辑）、特征间存在依赖链、规则需支持条件判断/决策表/评分卡多种评估方式、业务逻辑频繁变更需要免发版热更新、多产品线需独立规则集、同一订单多被保人需避免重复调用下游等服务 |
| **达到了什么效果** | 新增特征/规则类型零侵入、修改业务逻辑只需改配置/脚本无需发版、同类下游调用自动合并减少网络开销、特征依赖自动解析分层并发执行、跨保单实体隔离不串数据、决策过程完整可追溯 |

### 5.2 分模块详解

#### 特征引擎

| 用了什么 | 解决了什么问题 | 达到了什么效果 |
|---------|--------------|--------------|
| **策略模式** + Spring `List<FeatureCalcHandler>` 自动注入，6 种 CalcType 各对应一个 Handler | 特征取数来源多样：入参直取（PARAM_MAPPING）、下游 HTTP（EXTERNAL_API）、本地计算（EXPRESSION）、自定义 Java（CUSTOM）等 | 新增计算类型只需加枚举 + 写 Handler + 注册 Bean，核心流水线零改动。开闭原则：对扩展开放，对修改关闭 |
| **Kahn 拓扑排序** + BFS 依赖加载，层间串行、层内并发 | 特征间存在 `A → B → C` 依赖链，被依赖的特征必须先执行，且结果要存在上下文中供下游取用 | 配置时声明 `dependsOn` 即可，系统自动排序分层。依赖特征不出现在最终结果中。避免人工维护执行顺序 |
| **4 级 Aggregation × 4 级 Storage 矩阵路由**，`FeatureResultDispatcher` 分发 | 同一特征可能 ORDER 聚合一次调下游，结果却要分发到每个被保人/投保人；也可能 POLICY 聚合逐保单调用 | 16 格矩阵精确控制执行粒度与存储层级。向上路由自动拒绝。一次 ORDER 级 API 调用覆盖全订单所有被保人 |
| **FeatureTargeting** 三映射 + `while(changed)` 迭代传播 | 上游只传 `(policyId, insuredId) → featureCodes`，系统内需要反向查 `featureCode → entities` 才能按实体调度执行 | 自动构建正反向映射，依赖 target 沿链传播。精确到 `(policyId, insuredId)` 对，防止跨保单串特征 |
| **deepMerge** 请求合并 | 同服务多特征各自 buildRequest，如果不合并会导致 N 次重复 HTTP 调用（如 BASE_RISK、RISK_SCORE、FRAUD_CHECK 调同一个下游） | 同 key Map 递归合并、List 拼接，合并为一次 HTTP 调用后各自 extractFeatures。减少 N-1 次网络往返 |
| **Groovy 脚本引擎** + Caffeine 编译缓存 + 双重驱逐 | 下游 API 入参/出参映射逻辑频繁变化，表达式计算规则不断新增，如果都写 Java 类需要频繁发版 | 修改 DB 脚本即生效，不需要发版。支持 INPUT/OUTPUT/EXPRESSION 三种类型。编译缓存命中后性能等同 Java |
| **CompletableFuture 异步** + 专用线程池 + MDC 传播 | PARAM_MAPPING 纯 CPU 操作同步执行即可，EXTERNAL_API 等 IO 操作不能阻塞主线程 | 同步/异步自动分派。MDC 在异步边界显式捕获恢复，日志 traceId 不丢失。线程池 CallerRunsPolicy 防溢出 |

#### 规则引擎

| 用了什么 | 解决了什么问题 | 达到了什么效果 |
|---------|--------------|--------------|
| **策略模式** + `RuleEngineFactory` + 3 种 `RuleEvaluator` | 核保规则需要支持 AND/OR 条件判断、二维决策矩阵、多维评分卡等多种评估方式 | 新增评估类型只需加枚举 + 实现接口 + 注册工厂。规则数据（JSON DSL）可运行时修改即生效 |
| **ConditionListEvaluator** + 12 种运算符 + 嵌套 AND/OR | 简单条件判断如"年龄≥18 且 信用分≥600"，复杂嵌套如"(条件A 且 条件B) 或 条件C" | 支持任意深度嵌套逻辑、12 种比较运算符、点号分隔路径取值 `ins.creditScore.score` |
| **CrossDecisionTableEvaluator** + `*` 通配 + defaultResult | 二维组合决策如"风险等级 HIGH × 职业 STUDENT = 拒绝"，但 "风险等级 HIGH × 职业 * = 通过" | 表格数据驱动的决策逻辑，通配符降级匹配，默认值兜底。表格内容运行时在线修改 |
| **ScorecardEvaluator** + 多维评分 + 公式引擎 + 分桶 | 多维度加权打分如"年龄分×0.3 + 信用分×0.5 + 职业分×0.2"，然后按总分段分桶判定 pass/fail | 权重和维度可配置，公式支持四则运算和括号。内嵌手写递归下降解析器，零外部依赖 |
| **WordingResolver** + `{{macro}}` 模板 + 正则替换 | 不同端（客户/核保/运营）需要不同话术，话术中需要嵌入动态特征值 | 按 A/B/C 端 + pass/fail 场景自动选择模板，`{{path}}` 宏支持嵌套路径，`Matcher.quoteReplacement` 安全转义 |
| **productCode** 过滤 + priority 排序 | 多产品线共用同一部署，各自规则集不同；规则执行顺序需要可控 | 按产品码精确匹配规则，按优先级升序评估。同一部署支撑多个业务线 |
| **历史归档** + ChangeType 枚举 | 规则变更需要完整审计追溯：谁改了？改之前是什么？什么时候改的？ | Create/Update/Delete 自动快照到 `t_underwriting_rule_history`。按 rule_code 索引查询，支持完整变更追溯 |

#### 架构与基础设施

| 用了什么 | 解决了什么问题 | 达到了什么效果 |
|---------|--------------|--------------|
| **DDD 分层**（common/domain/application/infrastructure/interfaces/bootstrap/sdk） | 传统单体 MVC 跨层随意调用，核心逻辑与框架耦合，难以单独测试和替换基础设施 | domain 层零框架依赖；application 层不依赖 infrastructure；各层可独立单元测试；基础设施实现可替换 |
| **SDK 独立发布** + Feign + Nacos | 上游业务系统需要调用核保特征提取，如果直接耦合代码或手动拼 HTTP 请求维护成本高 | 上游 `@Autowired FeatureExtractionClient` 即可远程调用，SDK 封装 DTO + 序列化 + 服务发现 |
| **Redis 多命名空间缓存**（7 个 prefix）+ Caffeine 本地缓存 | DB 频繁查询特征配置/脚本/规则导致性能瓶颈，同时每个特征的计算结果也需缓存避免重复计算 | 配置/规则/脚本按实体粒度缓存，TTL 独立可配；特征结果按 feature 粒度 TTL 可配；SCAN 批量逐出生产安全 |
| **双重驱逐模式** | 特征配置有更新时，关联脚本的 Redis 缓存和 Caffeine 编译缓存可能出现竞态不一致（新 FC 配置引用旧脚本） | FC 缓存驱逐前、后各清一次脚本缓存，收窄并发窗口。两级缓存一致性得到保障 |
| **零拷贝上下文树** + 双向引用 | 特征计算时需要访问订单、保单、被保人、投保人各层的实体字段和已计算特征，如果拷贝会导致内存膨胀 | 只持有实体引用、O(1) 索引查找、双向引用任意层级导航。同一被保人在不同保单中有隔离的上下文实例 |
| **一键清除所有缓存**（POST /api/cache/clear-all） | 运维场景需要紧急清空所有缓存（Redis + 本地 Caffeine），如果没有统一入口排查困难 | 一次调用清除 7 个 Redis 前缀 + 本地 Caffeine 编译缓存。SCAN 迭代删除避免阻塞 Redis |

---

**核心设计哲学：配置驱动、脚本灵活、策略可插拔、数据与逻辑分离。**
