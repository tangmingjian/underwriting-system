# Change Log

## 2026-06-05: convertToResult 改用逐实体过滤 — 修复同保单不同被保人特征串扰

**修改文件**:
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/impl/FeatureExtractionServiceImpl.java`

**改动**:
- `convertToResult` 不再用全局 `Set<String> requestedCodes` 过滤，改为从 `FeatureTargeting` 按实体逐实体获取需求：
  - 被保人：`ft.getNeededFeaturesForInsured(policyId, insuredId)`
  - 投保人：`ft.getNeededFeaturesForApplicant(policyId, applicantId)`
  - 保单级：`ft.collectFeatureCodesForPolicy(policyId)`
  - 订单级：`ft.collectAllFeatureCodes()`
- `filterRequested` 的 `allowed` 参数改为可 null：null 不过滤（无映射场景）、空集全滤。
- 去掉 `extract()` 中不再需要的 `originalCodes` 局部变量。

**原因**: 上一版用 `request.getFeatureCodes()` 的全局并集过滤，但该并集不能区分不同实体的需求。场景：同保单下被保人 1 需要 A，被保人 2 需要 B，A 依赖 B——B 因依赖传播被存入被保人 1 的上下文，全局并集 `{A, B}` 无法把 B 从被保人 1 的出参中滤掉。现在按每个实体自己的入参需求分别过滤。

---

## 2026-06-05: MDC 透传 — 异步线程日志上下文

**修改文件**:
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/impl/FeatureExtractionServiceImpl.java`

**改动**:
- `dispatchFeature`: 非 PARAM_MAPPING 类型在 `runAsync` 前捕获 `MDC.getCopyOfContextMap()`，在异步线程中 `MDC.setContextMap()` + `try/finally MDC.clear()`。
- `executeGroups` 批处理: 同上，批处理 lambda 提交前捕获、内部恢复。

**原因**: 特征取数主线程的 MDC（如 traceId）未传递给异步线程，`CompletableFuture.runAsync` 提交的任务日志丢失调用链上下文，排查问题困难。`MDC.getCopyOfContextMap()` 必须在 lambda 外部（主线程）调用，不能写在 `runAsync(() -> { ... })` 内部。`finally` 中 `MDC.clear()` 防止线程回池后残留上下文污染后续任务。

---

## 2026-06-05: convertToResult 按入参过滤出参

**修改文件**:
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/impl/FeatureExtractionServiceImpl.java`
- `underwriting-application/src/test/java/com/insurance/uw/application/service/FeatureExtractionServiceImplTest.java`

**改动**:
- `convertToResult` 新增 `Set<String> requestedCodes` 参数，用 `filterRequested()` 对每个层级的特征 Map 做 `keySet().retainAll(requestedCodes)`，只保留入参要求的特征码。
- `extract()` 入口提取 `originalCodes = request.getFeatureCodes()`（从 `policyInsuredFeatureMap` + `policyApplicantFeatureMap` 汇总），透传给 `convertToResult`。
- 测试 3.2 断言从 `containsKeys("A","B","C")` 改为 `containsOnlyKeys("C")`。
- 修复 2 个测试 mock（`BASE_RISK` 相关）使其与真实 `ParamMappingCalcHandler` 返回值格式一致（featureCode 作为 Map key 包装）。

**原因**: `convertToResult` 构建出参时全量输出上下文树中的所有特征（含依赖展开的中间特征如 A→B→C 链中的 A、B），调用方只需 C 却收到了 A、B、C。依赖特征仍需执行和存入上下文树（供 `resolveFeatureFromContext` 查找），只在最终出参层过滤。过滤基于 featureCode 作为 Map key，依赖 `ParamMappingCalcHandler` 始终用 `Collections.singletonMap(fc.getFeatureCode(), value)` 包装的约定。

---

## 2026-06-04: evictScriptCache 清除 Redis + 本地全部缓存

**修改文件**:
- `underwriting-domain/src/main/java/com/insurance/uw/domain/repository/FeatureConfigRepository.java`
- `underwriting-domain/src/main/java/com/insurance/uw/domain/repository/FeatureScriptRepository.java`
- `underwriting-infrastructure/src/main/java/com/insurance/uw/infrastructure/persistence/FeatureConfigRepositoryImpl.java`
- `underwriting-infrastructure/src/main/java/com/insurance/uw/infrastructure/persistence/FeatureScriptRepositoryImpl.java`
- `underwriting-application/src/main/java/com/insurance/uw/application/service/FeatureConfigApplicationService.java`
- `underwriting-application/src/test/java/com/insurance/uw/application/service/FeatureConfigApplicationServiceTest.java`

**改动**:
- `FeatureConfigRepository` 接口新增 `evictCache(String featureCode)` 方法。
- `FeatureScriptRepository` 接口新增 `evictCache(String scriptId)` 方法。
- 两个 Impl 类实现：分别清除对应的 Redis 单 key + `__ALL__` key。
- `evictScriptCache` 调用顺序改为：先 `repository.evictCache(featureCode)`（清 Redis FC 缓存），再查 DB 拿最新配置，最后逐条清 `inputScriptId`/`outputScriptId` 的 Redis + Groovy 缓存。
- 去掉 `evictRelatedCaches` 中对 `featureCode` 的重复 Groovy 清除。
- `update()` 方法显式补上 `groovyEngine.evictScript(config.getFeatureCode())`。

**原因**: `FeatureConfigApplicationService.evictScriptCache()` 只清除了本地 Caffeine 的 Groovy 编译类缓存，完全没有清除 Redis（`uw:fc:{code}`、`uw:script:{id}`、各 `__ALL__` 列表缓存）。`findByFeatureCode` 本身读 Redis，缓存未清除会导致拿到过期的 `inputScriptId`/`outputScriptId`。

---

## 2026-06-04: 移除 underwriting-feature 模块 & 创建 SDK

**修改文件**:
- `underwriting-feature-sdk/pom.xml` (新建)
- `underwriting-feature-sdk/src/main/java/com/insurance/uw/sdk/feature/FeatureExtractionRequest.java` (移入)
- `underwriting-feature-sdk/src/main/java/com/insurance/uw/sdk/feature/FeatureExtractionResult.java` (移入)
- `underwriting-feature-sdk/src/main/java/com/insurance/uw/sdk/feature/FeatureExtractionClient.java` (新建)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/handler/FeatureCalcHandler.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/handler/ExternalApiCalcHandler.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/handler/ParamMappingCalcHandler.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/handler/ExpressionCalcHandler.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/handler/DatabaseQueryCalcHandler.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/handler/CompositeCalcHandler.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/impl/FeatureExtractionServiceImpl.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/feature/routing/FeatureResultDispatcher.java` (移入)
- `underwriting-application/src/main/java/com/insurance/uw/application/service/FeatureExtractionService.java` (新建，内部接口)
- `underwriting-application/pom.xml`
- `underwriting-bootstrap/pom.xml`
- `underwriting-bootstrap/.../ApplicationServiceConfiguration.java`
- `underwriting-interfaces/.../FeatureExtractionController.java`
- `underwriting-interfaces/.../UnderwritingController.java`
- `underwriting-interfaces/.../RuleEvaluationController.java`
- `underwriting-application/.../RuleApplicationService.java`
- `pom.xml`
- 6 个测试文件
- `underwriting-feature/` 目录 (删除)

**原因**: `underwriting-feature` 模块混合了 API 契约（`api/`：DTO + Feign 接口）和核心实现（`core/`：handler、orchestrator、routing），违反 DDD 分层原则。将 API 契约抽出为独立 SDK jar 供上游服务依赖，核心实现归入 `underwriting-application`。
