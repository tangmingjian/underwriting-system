# 特征取数执行模型

`FeatureExtractionServiceImpl#extract()` 采用**两层调度**模型：拓扑层间串行、层内并行。

## 整体流程

```
extract(request)
  ├── 1. 构建上下文树 (OrderFeatureContext)
  ├── 2. 传递依赖展开 (BFS)
  ├── 3. 拓扑排序 → List<Set<String>> layers
  ├── 4. 按层执行
  │     for layer in layers:          ← 层间串行
  │         executeLayer(layer)        ← 层内并行
  └── 5. 上下文 → FeatureExtractionResult
```

## 一、层间：串行

`FeatureDependencyResolver.topoSort()` 使用 Kahn 算法进行拓扑排序：

- **同层**特征：入度同时为 0，彼此无依赖，可并发
- **不同层**：第 n 层特征依赖第 n-1 层的结果，必须等上一层完成
- **依赖方向校验**：只允许 INSURED → APPLICANT → POLICY → ORDER（低→高），禁止反向依赖
- **循环依赖**会被检测并抛出异常

## 二、层内：并行 (executeLayer)

同层按 `AggregationLevel` 分成 4 组，全部以 `CompletableFuture` 提交到线程池，`allOf().join()` 等待全部完成：

```
executeLayer()
  ├── executeOrderLayer()      ──┐
  ├── executePolicyLayer()     ──┼── 4 组并行
  ├── executeInsuredLayer()    ──┤
  └── executeApplicantLayer()  ──┘
        ↓
  CompletableFuture.allOf(...).join()
```

## 三、各组内部逻辑

### ORDER 级 (executeOrderLayer)

整个订单执行一次。特征按 `serviceKey` 分组：

```
特征按 serviceKey 分组
  ├── group-A: [F1, F2, F3]  (同服务, EXTERNAL_API, size>1)
  │     → canBatch=true → executeBatch() 合并为 1 次 RPC，1 个 Future
  ├── group-B: [F4]  (单独)
  │     → 1 个 Future
  └── group-C: [F5, F6]  (PARAM_MAPPING)
        → canBatch=false → 每个特征独立 Future（并行）
```

- 并行：不同 group 之间
- 串行合并：同服务 EXTERNAL_API 合并为一个 `executeBatch()`，内部一次 RPC 拿到多个特征结果

### POLICY 级 (executePolicyLayer)

每个保单独立执行：

```
每保单 × 每 serviceKey 组
  ├── POL_001 × group-A → 1 Future（可能批处理）
  ├── POL_001 × group-B → 1 Future
  ├── POL_002 × group-A → 1 Future
  └── POL_002 × group-B → 1 Future
```

- 并行：所有 保单×组 组合全部并行
- 批处理逻辑同 ORDER 级

### INSURED 级 (executeInsuredLayer)

每个被保人独立执行，按 `policyInsuredFeatureMap` 过滤：

```
每被保人 × 每特征
  ├── INS_001 × F1 → 1 Future  (needed.contains(F1))
  ├── INS_001 × F2 → 1 Future  (needed.contains(F2))
  ├── INS_002 × F1 → 1 Future
  └── INS_002 × F2 → 1 Future
        ...
```

- 全部并行：每个 (被保人, 特征) 组合一个 Future
- 无批处理：EXTERNAL_API 也不会合并（每特征独立调用）

### APPLICANT 级 (executeApplicantLayer)

每个投保人独立执行，按 `policyApplicantFeatureMap` 过滤：

```
每投保人 × 每特征
  ├── APP_001 × F1 → 1 Future
  └── APP_001 × F2 → 1 Future
        ...
```

- 全部并行，同 INSURED 级
- 无批处理

## 四、批处理条件

`canBatch()` 仅在以下条件同时满足时为 `true`：

1. `featureCodes.size() > 1` — 同组有多个特征
2. `calcType == EXTERNAL_API` — 计算类型为外部 API

满足时调用 `handler.executeBatch(ctx, cfgs)` 合并为一次批量调用。

> 目前只有 ORDER 和 POLICY 级有 `groupByServiceKey` + `canBatch` 逻辑。
> INSURED / APPLICANT 级不做分组和批处理。

## 五、总结

| 维度 | 串行 | 并行 |
|---|---|---|
| 拓扑层之间 | 第 n 层 → 第 n+1 层 | — |
| 同层 AggregationLevel | — | ORDER / POLICY / INSURED / APPLICANT 同时跑 |
| ORDER 级内部 | 批处理组内特征合为 1 次 RPC | 各组独立 Future；非批处理特征各自并行 |
| POLICY 级内部 | 同上 | 每保单×每组 全部并行 |
| INSURED 级内部 | 无批处理 | 每被保人×每特征 全部并行 |
| APPLICANT 级内部 | 无批处理 | 每投保人×每特征 全部并行 |

## 六、并行度估算示例

假设：2 个保单，每保单 2 个被保人 + 1 个投保人，同层 5 个特征（其中 3 个 EXTERNAL_API 同服务）

| 层级 | 任务数 |
|---|---|
| ORDER 级 | 3 组（批处理组合并为 1 个 + 2 个独立 Future） |
| POLICY 级 | 2 保单 × 3 组 = 6 |
| INSURED 级 | 4 被保人 × 5 特征 = 20 |
| APPLICANT 级 | 2 投保人 × 5 特征 = 10 |
| **同层总计** | **~39 个 Future** |

线程池配置（默认）：core=8, max=16, queue=200，CallerRunsPolicy 拒绝策略。
