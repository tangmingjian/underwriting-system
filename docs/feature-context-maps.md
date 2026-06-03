# OrderFeatureContext 中的四个 Map 说明

## 场景设定

一个订单有 2 个保单、3 个被保人：

```
POL_001
├── INS_001 (张三)
└── INS_002 (李四)
POL_002
└── INS_003 (王五)
```

规则配置：**INS_001 需要 featA，INS_002 需要 featB**；且 **featA 依赖 featB**。

---

## 1. `policyInsuredFeatureMap` — 原始需求（谁直接需要什么）

由调用方（`RuleApplicationService`）根据规则推导后注入，结构是 `保单 → 被保人 → 特征集合`。

```json
{
  "POL_001": {
    "INS_001": ["featA"],
    "INS_002": ["featB"]
  }
}
```

**这是原始输入**，只记录了"直接需要"的关系，不包含依赖特征。比如 `featB` 作为 `featA` 的依赖，在这里对 `INS_001` 来说是"间接"的。

---

## 2. `policyApplicantFeatureMap` — 同上，但是投保人维度

结构相同：`保单 → 投保人 → 特征集合`。如果规则只关心被保人，这个 Map 通常为空。

---

## 3. `featureInsuredTargetMap` — 传播后的结果（包含依赖）

由 `FeatureExtractionServiceImpl.buildFeatureInsuredTargetMap()` 计算，结构反转为 `特征码 → 被保人ID集合`。

从 `policyInsuredFeatureMap` 出发，把目标**沿着依赖链反向传播**：

```
初始（来自 policyInsuredFeatureMap）:
  featA → {INS_001}
  featB → {INS_002}

传播（featA dependsOn featB）:
  featB 还要继承 featA 的目标 → featB ∪= {INS_001}

最终:
  featA → {INS_001}
  featB → {INS_001, INS_002}
```

**解决的核心问题**：没有这个 Map，`getInsuredsForFeature("featB")` 在 `policyInsuredFeatureMap` 中只能找到 `{INS_002}`，会漏掉 `INS_001`（它间接需要 featB）。有了传播后的 Map，`featB` 就知道它需要为 `{INS_001, INS_002}` 计算。

---

## 4. `featurePolicyTargetMap` — 同上，但是保单维度

结构：`特征码 → 保单ID集合`。用于 `getPoliciesForFeature()`，确定一个 ORDER 级特征需要写入哪些保单。

```json
{
  "featA": ["POL_001"],
  "featB": ["POL_001"]
}
```

---

## 一张图总结

```
policyInsuredFeatureMap          featureInsuredTargetMap
(原始输入，谁直接需要)              (传播后，含依赖，反转为特征→人)
┌──────────────────────┐          ┌──────────────────────────┐
│ POL_001:             │          │ featA → {INS_001}        │
│   INS_001 → [featA]  │  传播    │ featB → {INS_001,INS_002}│
│   INS_002 → [featB]  │ ──────→ │                          │
└──────────────────────┘          └──────────────────────────┘
         ↓                                ↓
  只记录直接需求                    依赖特征也有了目标，不会
  featB 对 INS_001 是"间接"的       fallback 到全部被保人
```

**简单记忆**：`policyXxxMap` 是"谁直接要什么"（保单→人→特征），`featureXxxTargetMap` 是"特征需要算给谁"（特征→人/保单），后者在前者基础上补全了依赖传播。
