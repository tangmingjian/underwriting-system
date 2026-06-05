# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build (all modules)
mvn compile

# Full build skipping tests
mvn compile -DskipTests

# Install SDK module first (needed when application module depends on it)
mvn install -pl underwriting-feature-sdk,underwriting-domain,underwriting-infrastructure -q -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -pl underwriting-application -Dtest="FeatureExtractionServiceImplTest"

# Run a specific @Nested inner test class
mvn test -pl underwriting-application -Dtest="FeatureExtractionServiceImplTest\$DependencyChain"

# Run a specific test method
mvn test -pl underwriting-application -Dtest="FeatureExtractionServiceImplTest\$DependencyChain#twoLayerChain"

# Run tests in a specific module
mvn test -pl underwriting-domain
```

## Architecture

### Module Dependency (top-down DDD)

```
underwriting-bootstrap     → Spring Boot entry, @Configuration assembly
underwriting-interfaces    → REST controllers
underwriting-application   → service orchestration, feature handlers, result routing
underwriting-feature-sdk   → DTOs + Feign client for upstream services
underwriting-domain        → entities, context tree, repository interfaces, domain services
underwriting-infrastructure → MyBatis, Redis, Groovy engine, downstream HTTP client
underwriting-common        → enums (CalcType, AggregationLevel, StorageLevel, etc.)
```

`underwriting-feature-sdk` is published as a standalone jar so upstream services can call feature extraction remotely via `@FeignClient(name = "underwriting-service", path = "/api/feature")`.

### Feature Extraction Pipeline (core business flow)

The central entry point is `FeatureExtractionServiceImpl.extract(request)`, a 4-phase pipeline:

1. **Build Context Tree**: `OrderFeatureContext` → `PolicyFeatureContext` × N → `InsuredFeatureContext` / `ApplicantFeatureContext`. `FeatureTargeting` is injected as the navigation map carrying which entity needs which feature.

2. **Load Configs with Dependencies**: BFS from `featureConfigRepository.findByFeatureCodes()` following `dependsOn` chains. Dependency features (not directly requested) are executed and stored in context for downstream resolution but filtered out of the final result.

3. **Build Derived Maps**: `FeatureTargeting.buildDerivedMaps(configMap)` creates `featureInsuredTargetMap`, `featurePolicyTargetMap`, `featureInsuredPolicyMap` for precise per-entity dispatch.

4. **Topological Sort + Layered Execution**: Kahn algorithm via `FeatureDependencyResolver.topoSort()`. Each layer groups by `AggregationLevel` (ORDER/POLICY/INSURED/APPLICANT), then executes:
   - `PARAM_MAPPING`: synchronous on main thread (pure CPU, no thread-pool overhead)
   - Other `CalcType`: `CompletableFuture.runAsync` via the `uw-feature-*` thread pool
   - Same-service `EXTERNAL_API` features are merged into a single batch HTTP call via `deepMerge`

### Handler → Dispatcher → Context Tree → Result

```
Handler.execute(ctx, fc) → Map<targetId, rawValue>
  ↓
FeatureResultDispatcher.dispatch(ctx, fc, results)
  → Routes by AggregationLevel × StorageLevel (4×4 matrix, downward only)
  → Stores to context tree at correct level
  ↓
convertToResult(orderCtx, requestedCodes)
  → Flattens context tree to FeatureExtractionResult
  → Filters out dependency-chain-only features
```

### Context Tree (4-level hierarchy)

| Context | Holds | Feature Map |
|---------|-------|-------------|
| `OrderFeatureContext` | order, `List<PolicyFeatureContext>`, `FeatureTargeting` | `orderFeatures` |
| `PolicyFeatureContext` | policy, `ApplicantFeatureContext`, `List<InsuredFeatureContext>` | `policyFeatures` |
| `InsuredFeatureContext` | insured, parent policy context | `acquiredFeatures` |
| `ApplicantFeatureContext` | applicant, parent policy context | `features` |

Same insured/applicant appearing in multiple policies has **separate context instances per policy** — isolation is by `(policyId, entityId)`.

### Cache Layers

| Cache | Store | Key Pattern | Evicted By |
|-------|-------|-------------|------------|
| FeatureConfig | Redis | `uw:fc:{code}`, `uw:fc:__ALL__` | `FeatureConfigRepository.evictCache()` |
| FeatureScript | Redis | `uw:script:{id}`, `uw:script:__ALL__` | `FeatureScriptRepository.evictCache()` |
| Groovy compiled class | Caffeine (local) | `scriptId` | `GroovyMappingEngine.evictScript()` |
| Feature results | Redis | managed by `FeatureResultCache` | TTL-based expiry |

When evicting script caches (`FeatureConfigApplicationService.evictScriptCache`), both Redis script cache AND local Caffeine Groovy class cache must be cleared. Redis FC cache is evicted first so `findByFeatureCode` returns fresh data.

### Key Conventions

- **ParamMappingCalcHandler** always wraps return values with `Collections.singletonMap(fc.getFeatureCode(), value)` — the feature code IS the top-level key in stored context maps. Test mocks must match this format.
- **MDC propagation**: `dispatchFeature` and batch `executeGroups` capture `MDC.getCopyOfContextMap()` before `CompletableFuture.runAsync` and restore with `try { ... } finally { MDC.clear(); }`.
- **Spring profile `mock`**: uses `MockDownstreamApiClient` instead of real HTTP calls. Active by default in `application.yml`.
- **Java 21, Spring Boot 3.3.5, MyBatis-Plus 3.5.7, Groovy 4.0.22**
