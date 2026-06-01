# 特征取数 & 核保评估 — 测试请求报文

本文档提供各场景的测试请求 JSON，以及模拟下游 API 响应，用于端到端测试。

---

## 目录

1. [API 端点说明](#1-api-端点说明)
2. [基础测试数据（共享 Order）](#2-基础测试数据)
3. [场景 1~5：PARAM_MAPPING 入参取数](#3-场景-15param_mapping-入参取数)
4. [场景 6~9：EXTERNAL_API 外部调用](#4-场景-69external_api-外部调用)
5. [场景 10~11：特征依赖 + 批量拆分](#5-场景-1011特征依赖--批量拆分)
6. [场景 12~13：INSURED / APPLICANT 聚合](#6-场景-1213insured--applicant-聚合)
7. [场景 14：ORDER 级规则评估](#7-场景-14order-级规则评估)
8. [Mock 下游响应汇总](#8-mock-下游响应汇总)

---

## 1. API 端点说明

| 端点 | 方法 | 请求体 | 说明 |
|---|---|---|---|
| `/api/underwriting/evaluate` | POST | `Order` | 特征取数 + 规则评估，返回 `List<UnderwritingResult>` |
| `/api/underwriting/extract` | POST | `Order` | 仅特征取数，返回 `FeatureExtractionResult` |
| `/api/feature/extract` | POST | `FeatureExtractionRequest` | 独立特征取数，可指定特征码 |

---

## 2. 基础测试数据

以下为所有场景共用的基础 Order JSON，包含 2 个保单、2 个投保人、4 个被保人：

```json
{
  "id": "ORD_20260601_001",
  "channel": "ONLINE",
  "orderTime": "2026-06-01T10:30:00",
  "policies": [
    {
      "id": "POL_001",
      "product": {
        "productCode": "HEALTH_A_001",
        "productName": "健康险A款"
      },
      "applicant": {
        "id": "APP_001",
        "idNo": "110101199003151234",
        "name": "张建国",
        "age": 36,
        "gender": "M",
        "customerNos": ["CUST_A_001", "CUST_A_002", "CUST_A_003"]
      },
      "insureds": [
        {
          "id": "INS_001",
          "idNo": "110101199003151234",
          "name": "张建国",
          "age": 36,
          "gender": "M",
          "occupation": "软件工程师",
          "phone": "13800001111",
          "customerNos": ["CUST_A_001", "CUST_A_002", "CUST_A_003"]
        },
        {
          "id": "INS_002",
          "idNo": "110101199506152345",
          "name": "李美玲",
          "age": 31,
          "gender": "F",
          "occupation": "医生",
          "phone": "13800002222",
          "customerNos": ["CUST_B_001", "CUST_B_002"]
        }
      ],
      "declaredIncome": 300000,
      "appliedAmount": 1000000
    },
    {
      "id": "POL_002",
      "product": {
        "productCode": "ACCIDENT_B_002",
        "productName": "意外险B款"
      },
      "applicant": {
        "id": "APP_002",
        "idNo": "110101198812016789",
        "name": "王大明",
        "age": 38,
        "gender": "M",
        "customerNos": ["CUST_C_001"]
      },
      "insureds": [
        {
          "id": "INS_003",
          "idNo": "110101198812016789",
          "name": "王大明",
          "age": 38,
          "gender": "M",
          "occupation": "建筑工人",
          "phone": "13800003333",
          "customerNos": ["CUST_C_001"]
        },
        {
          "id": "INS_004",
          "idNo": "110101199210019876",
          "name": "赵小红",
          "age": 34,
          "gender": "F",
          "occupation": "会计",
          "phone": "13800004444",
          "customerNos": ["CUST_D_001", "CUST_D_002"]
        }
      ],
      "declaredIncome": 150000,
      "appliedAmount": 200000
    }
  ]
}
```

**数据关系图**：

```
订单 ORD_20260601_001 (ONLINE)
├── 保单 POL_001 (HEALTH_A_001, 保额 100万)
│   ├── 投保人 APP_001 张建国 (36/M) customerNos: [CUST_A_001, CUST_A_002, CUST_A_003]
│   ├── 被保人 INS_001 张建国 (36/M, 软件工程师) customerNos: [CUST_A_001, CUST_A_002, CUST_A_003]
│   └── 被保人 INS_002 李美玲 (31/F, 医生)       customerNos: [CUST_B_001, CUST_B_002]
│
└── 保单 POL_002 (ACCIDENT_B_002, 保额 20万)
    ├── 投保人 APP_002 王大明 (38/M) customerNos: [CUST_C_001]
    ├── 被保人 INS_003 王大明 (38/M, 建筑工人)    customerNos: [CUST_C_001]
    └── 被保人 INS_004 赵小红 (34/F, 会计)        customerNos: [CUST_D_001, CUST_D_002]
```

---

## 3. 场景 1~5：PARAM_MAPPING 入参取数

### 场景 1：被保人年龄 (PARAM_MAPPING, POLICY→INSURED)

**特征配置**：
```
feature_code=ins.age, calc_type=PARAM_MAPPING
calc_config={"source":"insured.age"}
aggregation=POLICY, storage_level=INSURED
```

**请求**：使用基础 Order，`POST /api/underwriting/extract`

**预期结果**（FeatureExtractionResult.insuredFeatures）：
```json
{
  "insuredFeatures": {
    "INS_001": { "ins.age": 36 },
    "INS_002": { "ins.age": 31 },
    "INS_003": { "ins.age": 38 },
    "INS_004": { "ins.age": 34 }
  }
}
```

**对应规则**：`#root['ins.age'] >= 18` → 全部通过

---

### 场景 2：被保人职业 (PARAM_MAPPING, POLICY→INSURED)

**特征配置**：
```
feature_code=ins.occupation, calc_type=PARAM_MAPPING
calc_config={"source":"insured.occupation"}
aggregation=POLICY, storage_level=INSURED
```

**请求**：使用基础 Order

**预期结果**：
```json
{
  "insuredFeatures": {
    "INS_001": { "ins.occupation": "软件工程师" },
    "INS_002": { "ins.occupation": "医生" },
    "INS_003": { "ins.occupation": "建筑工人" },
    "INS_004": { "ins.occupation": "会计" }
  }
}
```

---

### 场景 3：订单渠道 (PARAM_MAPPING, ORDER→ORDER)

**特征配置**：
```
feature_code=ord.channel, calc_type=PARAM_MAPPING
calc_config={"source":"order.channel"}
aggregation=ORDER, storage_level=ORDER
```

**请求**：使用基础 Order

**预期结果**：
```json
{
  "orderFeatures": { "ord.channel": "ONLINE" }
}
```

**对应规则**：`#root['ord.channel'] == 'ONLINE'` → 通过

---

### 场景 4：投保金额 (PARAM_MAPPING, ORDER→POLICY)

**特征配置**：
```
feature_code=pol.appliedAmount, calc_type=PARAM_MAPPING
calc_config={"source":"policy.appliedAmount"}
aggregation=ORDER, storage_level=POLICY
```

**请求**：使用基础 Order

**预期结果**：
```json
{
  "policyFeatures": {
    "POL_001": { "pol.appliedAmount": 1000000 },
    "POL_002": { "pol.appliedAmount": 200000 }
  }
}
```

**对应规则（POLICY 级）**：`#root['pol.appliedAmount'] <= 500000` → POL_001 不通过，POL_002 通过

---

### 场景 5：被保人性别 (PARAM_MAPPING, POLICY→INSURED)

**特征配置**：
```
feature_code=ins.gender, calc_type=PARAM_MAPPING
calc_config={"source":"insured.gender"}
aggregation=POLICY, storage_level=INSURED
```

**请求**：使用基础 Order

**预期结果**：
```json
{
  "insuredFeatures": {
    "INS_001": { "ins.gender": "M" },
    "INS_002": { "ins.gender": "F" },
    "INS_003": { "ins.gender": "M" },
    "INS_004": { "ins.gender": "F" }
  }
}
```

---

## 4. 场景 6~9：EXTERNAL_API 外部调用

### 场景 6：信用评分 — ORDER 聚合批量取数 → INSURED 存储

**特征配置**：
```
feature_code=ins.creditScore, calc_type=EXTERNAL_API
aggregation=ORDER, storage_level=INSURED
calc_config.service.discovery_type=STATIC (双端点)
```

**Groovy 入参脚本 buildRequest(OrderFeatureContext ctx)**：
会遍历 `ctx.getInsuredsForFeature("ins.creditScore")` 收集 4 个被保人的信息拼装请求。

**实际发出的下游 HTTP 请求体**：
```json
{
  "orderNo": "ORD_20260601_001",
  "channelCode": "ONLINE",
  "queryType": "CREDIT_SCORE",
  "persons": [
    {
      "customerNos": ["CUST_A_001", "CUST_A_002", "CUST_A_003"],
      "name": "张建国",
      "idNo": "110101199003151234",
      "roleType": "INSURED",
      "refId": "INS_001"
    },
    {
      "customerNos": ["CUST_B_001", "CUST_B_002"],
      "name": "李美玲",
      "idNo": "110101199506152345",
      "roleType": "INSURED",
      "refId": "INS_002"
    },
    {
      "customerNos": ["CUST_C_001"],
      "name": "王大明",
      "idNo": "110101198812016789",
      "roleType": "INSURED",
      "refId": "INS_003"
    },
    {
      "customerNos": ["CUST_D_001", "CUST_D_002"],
      "name": "赵小红",
      "idNo": "110101199210019876",
      "roleType": "INSURED",
      "refId": "INS_004"
    },
    {
      "customerNos": ["CUST_A_001", "CUST_A_002", "CUST_A_003"],
      "name": "张建国",
      "idNo": "110101199003151234",
      "roleType": "APPLICANT",
      "refId": "APP_001"
    },
    {
      "customerNos": ["CUST_C_001"],
      "name": "王大明",
      "idNo": "110101198812016789",
      "roleType": "APPLICANT",
      "refId": "APP_002"
    }
  ]
}
```

**Mock 下游响应**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "scores": [
      { "customerNo": "CUST_A_001", "score": 720, "level": "A", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_A_002", "score": 680, "level": "B", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_A_003", "score": 750, "level": "A", "scoreTime": "2026-05-20T10:00:00" },
      { "customerNo": "CUST_B_001", "score": 650, "level": "B", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_B_002", "score": 610, "level": "C", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_C_001", "score": 580, "level": "C", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_D_001", "score": 700, "level": "B", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_D_002", "score": 690, "level": "B", "scoreTime": "2026-05-15T10:00:00" }
    ],
    "orderRiskLevel": "LOW"
  }
}
```

**合并逻辑**（取最高分）：

| 人 | 匹配的 customerNo | 分数 | 最终 |
|---|---|---|---|
| INS_001 张建国 | CUST_A_001(720), CUST_A_002(680), CUST_A_003(750) | max=750 | `{score:750, level:"A", custNo:"CUST_A_003"}` |
| INS_002 李美玲 | CUST_B_001(650), CUST_B_002(610) | max=650 | `{score:650, level:"B", custNo:"CUST_B_001"}` |
| INS_003 王大明 | CUST_C_001(580) | 580 | `{score:580, level:"C", custNo:"CUST_C_001"}` |
| INS_004 赵小红 | CUST_D_001(700), CUST_D_002(690) | max=700 | `{score:700, level:"B", custNo:"CUST_D_001"}` |
| APP_001 张建国 | CUST_A_001, CUST_A_002, CUST_A_003 | max=750 | `{score:750, level:"A", custNo:"CUST_A_003"}` |
| APP_002 王大明 | CUST_C_001 | 580 | `{score:580, level:"C", custNo:"CUST_C_001"}` |

**预期 FeatureExtractionResult**：
```json
{
  "insuredFeatures": {
    "INS_001": { "ins.creditScore": { "score": 750, "level": "A", "custNo": "CUST_A_003", "time": 1715731200000 } },
    "INS_002": { "ins.creditScore": { "score": 650, "level": "B", "custNo": "CUST_B_001", "time": 1715731200000 } },
    "INS_003": { "ins.creditScore": { "score": 580, "level": "C", "custNo": "CUST_C_001", "time": 1715731200000 } },
    "INS_004": { "ins.creditScore": { "score": 700, "level": "B", "custNo": "CUST_D_001", "time": 1715731200000 } }
  },
  "applicantFeatures": {
    "APP_001": { "ins.creditScore": { "score": 750, "level": "A", "custNo": "CUST_A_003" } },
    "APP_002": { "ins.creditScore": { "score": 580, "level": "C", "custNo": "CUST_C_001" } }
  },
  "orderFeatures": { "ins.creditScore": { "riskLevel": "LOW" } }
}
```

**对应规则**：`#root['ins.creditScore']['score'] >= 600` → INS_001(750)✓ INS_002(650)✓ INS_003(580)✗ INS_004(700)✓

---

### 场景 7：反欺诈风险 — ORDER 聚合 → ORDER 存储

**特征配置**：
```
feature_code=ord.fraudRiskScore, calc_type=EXTERNAL_API
aggregation=ORDER, storage_level=ORDER
calc_config.service.discovery_type=NACOS, service_name=risk-control-svc
```

**实际发出的下游 HTTP 请求体**：
```json
{
  "orderNo": "ORD_20260601_001",
  "channel": "ONLINE",
  "orderTime": "2026-06-01T10:30:00",
  "persons": [
    { "customerNos": ["CUST_A_001","CUST_A_002","CUST_A_003"], "name": "张建国", "idNo": "110101199003151234", "phone": "13800001111" },
    { "customerNos": ["CUST_B_001","CUST_B_002"], "name": "李美玲", "idNo": "110101199506152345", "phone": "13800002222" },
    { "customerNos": ["CUST_C_001"], "name": "王大明", "idNo": "110101198812016789", "phone": "13800003333" },
    { "customerNos": ["CUST_D_001","CUST_D_002"], "name": "赵小红", "idNo": "110101199210019876", "phone": "13800004444" }
  ]
}
```

**Mock 下游响应**：
```json
{
  "code": 0,
  "data": {
    "riskScore": 35,
    "riskLevel": "LOW",
    "hitRules": ["RULE_FRAUD_BASIC"],
    "hitCustomerNos": []
  }
}
```

**预期 FeatureExtractionResult**：
```json
{
  "orderFeatures": {
    "ord.fraudRiskScore": {
      "riskScore": 35,
      "riskLevel": "LOW",
      "hitRules": "RULE_FRAUD_BASIC",
      "hitCustNos": "",
      "time": 1715731200000
    }
  }
}
```

**对应规则**：`#root['ord.fraudRiskScore']['riskScore'] < 80` → 通过

---

### 场景 8：职业风险 — POLICY 聚合 → INSURED 存储

**特征配置**：
```
feature_code=ins.occupationRisk, calc_type=EXTERNAL_API
aggregation=POLICY, storage_level=INSURED
calc_config.service.discovery_type=DIRECT
```

**针对 POL_001 发出的 HTTP 请求**：
```json
{
  "policyNo": "POL_001",
  "productCode": "HEALTH_A_001",
  "insureds": [
    { "insuredId": "INS_001", "customerNos": ["CUST_A_001","CUST_A_002","CUST_A_003"], "idNo": "110101199003151234", "occupation": "软件工程师", "age": 36 },
    { "insuredId": "INS_002", "customerNos": ["CUST_B_001","CUST_B_002"], "idNo": "110101199506152345", "occupation": "医生", "age": 31 }
  ]
}
```

**针对 POL_002 发出的 HTTP 请求**：
```json
{
  "policyNo": "POL_002",
  "productCode": "ACCIDENT_B_002",
  "insureds": [
    { "insuredId": "INS_003", "customerNos": ["CUST_C_001"], "idNo": "110101198812016789", "occupation": "建筑工人", "age": 38 },
    { "insuredId": "INS_004", "customerNos": ["CUST_D_001","CUST_D_002"], "idNo": "110101199210019876", "occupation": "会计", "age": 34 }
  ]
}
```

**Mock 下游响应 (POL_001)**：
```json
{
  "code": 0,
  "data": {
    "occupationRisks": [
      { "customerNo": "CUST_A_001", "riskClass": 1, "riskDescription": "低风险职业" },
      { "customerNo": "CUST_B_001", "riskClass": 2, "riskDescription": "中低风险职业" }
    ]
  }
}
```

**Mock 下游响应 (POL_002)**：
```json
{
  "code": 0,
  "data": {
    "occupationRisks": [
      { "customerNo": "CUST_C_001", "riskClass": 4, "riskDescription": "高风险职业" },
      { "customerNo": "CUST_D_001", "riskClass": 1, "riskDescription": "低风险职业" }
    ]
  }
}
```

**预期 FeatureExtractionResult**：
```json
{
  "insuredFeatures": {
    "INS_001": { "ins.occupationRisk": { "riskClass": 1, "riskDesc": "低风险职业", "custNo": "CUST_A_001" } },
    "INS_002": { "ins.occupationRisk": { "riskClass": 2, "riskDesc": "中低风险职业", "custNo": "CUST_B_001" } },
    "INS_003": { "ins.occupationRisk": { "riskClass": 4, "riskDesc": "高风险职业", "custNo": "CUST_C_001" } },
    "INS_004": { "ins.occupationRisk": { "riskClass": 1, "riskDesc": "低风险职业", "custNo": "CUST_D_001" } }
  }
}
```

**对应规则**：`#root['ins.occupationRisk']['riskClass'] <= 3` → INS_003(4)✗

---

### 场景 9：产品保额上限 — POLICY 聚合 → POLICY 存储

**特征配置**：
```
feature_code=pol.maxSumAssured, calc_type=EXTERNAL_API
aggregation=POLICY, storage_level=POLICY
```

**Mock 下游响应 (POL_001)**：
```json
{
  "code": 0,
  "data": { "maxSumAssured": 2000000, "maxDailyPremium": 50000 }
}
```

**Mock 下游响应 (POL_002)**：
```json
{
  "code": 0,
  "data": { "maxSumAssured": 500000, "maxDailyPremium": 10000 }
}
```

**预期 FeatureExtractionResult**：
```json
{
  "policyFeatures": {
    "POL_001": { "pol.maxSumAssured": { "maxSumAssured": 2000000, "maxDailyPremium": 50000 } },
    "POL_002": { "pol.maxSumAssured": { "maxSumAssured": 500000, "maxDailyPremium": 10000 } }
  }
}
```

---

## 5. 场景 10~11：特征依赖 + 批量拆分

### 场景 10：BASE_RISK 批量查询 → 拆分为 RISK_SCORE / FRAUD_CHECK

**特征配置关系**：
```
BASE_RISK (EXTERNAL_API, ORDER→INSURED, depends_on=[])
  ├── RISK_SCORE  (PARAM_MAPPING, ORDER→INSURED, depends_on=["BASE_RISK"], source="feature.BASE_RISK.riskScore")
  └── FRAUD_CHECK (PARAM_MAPPING, ORDER→INSURED, depends_on=["BASE_RISK"], source="feature.BASE_RISK.fraudScore")
```

**独立请求**（指定特征码集合，系统自动展开依赖）：
```json
{
  "order": { "...基础Order..." },
  "featureCodes": ["RISK_SCORE", "FRAUD_CHECK"]
}
```

> 系统会自动展开为 `{BASE_RISK, RISK_SCORE, FRAUD_CHECK}`，拓扑排序后分 2 层执行。

**Mock 下游响应 (BASE_RISK)**：
```json
{
  "code": 0,
  "data": {
    "risks": [
      { "refId": "INS_001", "riskScore": 85, "fraudScore": 10, "amlFlag": false },
      { "refId": "INS_002", "riskScore": 72, "fraudScore": 25, "amlFlag": false },
      { "refId": "INS_003", "riskScore": 45, "fraudScore": 90, "amlFlag": true },
      { "refId": "INS_004", "riskScore": 68, "fraudScore": 15, "amlFlag": false }
    ]
  }
}
```

**第 1 层执行后，BASE_RISK 存入各被保人**：
```json
{
  "INS_001": { "BASE_RISK": { "riskScore": 85, "fraudScore": 10, "amlFlag": false } },
  "INS_002": { "BASE_RISK": { "riskScore": 72, "fraudScore": 25, "amlFlag": false } },
  "INS_003": { "BASE_RISK": { "riskScore": 45, "fraudScore": 90, "amlFlag": true } },
  "INS_004": { "BASE_RISK": { "riskScore": 68, "fraudScore": 15, "amlFlag": false } }
}
```

**第 2 层执行后，子特征提取完成**：
```json
{
  "insuredFeatures": {
    "INS_001": {
      "BASE_RISK": { "riskScore": 85, "fraudScore": 10, "amlFlag": false },
      "RISK_SCORE": 85,
      "FRAUD_CHECK": 10
    },
    "INS_002": {
      "BASE_RISK": { "riskScore": 72, "fraudScore": 25, "amlFlag": false },
      "RISK_SCORE": 72,
      "FRAUD_CHECK": 25
    },
    "INS_003": {
      "BASE_RISK": { "riskScore": 45, "fraudScore": 90, "amlFlag": true },
      "RISK_SCORE": 45,
      "FRAUD_CHECK": 90
    },
    "INS_004": {
      "BASE_RISK": { "riskScore": 68, "fraudScore": 15, "amlFlag": false },
      "RISK_SCORE": 68,
      "FRAUD_CHECK": 15
    }
  }
}
```

**对应规则**：
- `#root['RISK_SCORE'] >= 60` → INS_001(85)✓ INS_002(72)✓ INS_003(45)✗ INS_004(68)✓
- `#root['FRAUD_CHECK'] < 80` → INS_001(10)✓ INS_002(25)✓ INS_003(90)✗ INS_004(15)✓

---

### 场景 11：EXTERNAL_API 批处理（两个特征同服务，合并为一次调用）

**假设两个特征配置为同服务**：
```
feature_code=HEALTH_STATUS, calc_type=EXTERNAL_API
calc_config.service.static_endpoints=["https://api.risk.internal.com"]
calc_config.service.path="/v1/risk/batch"    ← 同 path
input_script_id="buildHealthReq"
output_script_id="extractHealthStatus"

feature_code=MEDICAL_HISTORY, calc_type=EXTERNAL_API
calc_config.service.static_endpoints=["https://api.risk.internal.com"]
calc_config.service.path="/v1/risk/batch"    ← 同 path
input_script_id="buildMedicalReq"
output_script_id="extractMedicalHistory"
```

> 调度器通过 `service_key` 识别两个特征同服务 → 合并为一次 HTTP 调用（`executeBatch`），两个入参脚本的请求体 `deepMerge`，两个出参脚本各自提取结果。

---

## 6. 场景 12~13：INSURED / APPLICANT 聚合

### 场景 12：健康评分 — INSURED 聚合（每人独立调接口）

**特征配置**：
```
feature_code=ins.healthScore, calc_type=EXTERNAL_API
aggregation=INSURED, storage_level=INSURED
```

> 4 个被保人 → 4 个 `CompletableFuture` 并行执行，互不阻塞。

**针对 INS_001 发出的 HTTP 请求**（Groovy ctx 是 InsuredFeatureContext）：
```json
{
  "customerNos": ["CUST_A_001", "CUST_A_002", "CUST_A_003"],
  "idNo": "110101199003151234",
  "name": "张建国",
  "age": 36,
  "gender": "M"
}
```

**Mock 下游响应**：

| 被保人 | 响应 |
|---|---|
| INS_001 | `{"code":0,"data":{"healthScore":95,"healthGrade":"EXCELLENT"}}` |
| INS_002 | `{"code":0,"data":{"healthScore":82,"healthGrade":"GOOD"}}` |
| INS_003 | `{"code":0,"data":{"healthScore":55,"healthGrade":"SUBSTANDARD"}}` |
| INS_004 | `{"code":0,"data":{"healthScore":78,"healthGrade":"GOOD"}}` |

**预期 FeatureExtractionResult**：
```json
{
  "insuredFeatures": {
    "INS_001": { "ins.healthScore": { "healthScore": 95, "healthGrade": "EXCELLENT" } },
    "INS_002": { "ins.healthScore": { "healthScore": 82, "healthGrade": "GOOD" } },
    "INS_003": { "ins.healthScore": { "healthScore": 55, "healthGrade": "SUBSTANDARD" } },
    "INS_004": { "ins.healthScore": { "healthScore": 78, "healthGrade": "GOOD" } }
  }
}
```

**对应规则**：`#root['ins.healthScore']['healthScore'] >= 60` → INS_003(55)✗

---

### 场景 13：投保人征信查询 — APPLICANT 聚合（每人独立）

**特征配置**：
```
feature_code=app.creditReport, calc_type=EXTERNAL_API
aggregation=APPLICANT, storage_level=APPLICANT
```

> 2 个投保人 → 2 个 CompletableFuture 并行执行。

**针对 APP_001 发出的 HTTP 请求**（Groovy ctx 是 ApplicantFeatureContext）：
```json
{
  "customerNos": ["CUST_A_001", "CUST_A_002", "CUST_A_003"],
  "idNo": "110101199003151234",
  "name": "张建国"
}
```

**Mock 下游响应**：

| 投保人 | 响应 |
|---|---|
| APP_001 | `{"code":0,"data":{"creditScore":780,"hasDefault":false,"loanCount":3}}` |
| APP_002 | `{"code":0,"data":{"creditScore":620,"hasDefault":true,"loanCount":8}}` |

**预期 FeatureExtractionResult**：
```json
{
  "applicantFeatures": {
    "APP_001": { "app.creditReport": { "creditScore": 780, "hasDefault": false, "loanCount": 3 } },
    "APP_002": { "app.creditReport": { "creditScore": 620, "hasDefault": true, "loanCount": 8 } }
  }
}
```

---

## 7. 场景 14：ORDER 级规则评估

**特征配置**：
```
ord.channel (PARAM_MAPPING, ORDER→ORDER)
ord.fraudRiskScore (EXTERNAL_API, ORDER→ORDER)
```

**规则配置 SQL**：
```sql
INSERT INTO t_underwriting_rule (rule_code, rule_name, rule_type, expression, feature_codes, product_code, priority, status) VALUES
('RULE_ORDER_001', '订单反欺诈检查', 'ORDER',
 '#root[''ord.fraudRiskScore''][''riskScore''] < 80 and #root[''ord.channel''] != null',
 'ord.fraudRiskScore,ord.channel', null, 1, 1);
```

**请求**：基础 Order，`POST /api/underwriting/evaluate`

**预期 UnderwritingResult 列表**（包含 INSURED/APPLICANT/POLICY/ORDER 全部规则结果）：
```json
[
  { "level": "ORDER", "targetId": "ORD_20260601_001", "targetName": null,
    "ruleCode": "RULE_ORDER_001", "ruleName": "订单反欺诈检查", "passed": true },
  { "level": "INSURED", "targetId": "INS_001", "targetName": "张建国",
    "ruleCode": "RULE_AGE_001", "ruleName": "被保人成年检查", "passed": true },
  ...
]
```

---

## 8. Mock 下游响应汇总

测试时可用 Mock Server（如 WireMock / Mockoon）配置以下端点：

| 端点 | 方法 | 场景 | 响应文件 |
|---|---|---|---|
| `/v2/credit/score` | POST | 场景 6 信用评分 | 见下 `credit-score-resp.json` |
| `/api/v1/fraud/score` | POST | 场景 7 反欺诈 | 见下 `fraud-risk-resp.json` |
| `/v1/risk/evaluate` (POL_001) | POST | 场景 8 职业风险 | 见下 `occupation-risk-pol001.json` |
| `/v1/risk/evaluate` (POL_002) | POST | 场景 8 职业风险 | 见下 `occupation-risk-pol002.json` |
| `/api/limit/query` (POL_001) | POST | 场景 9 保额上限 | 见下 `product-limit-pol001.json` |
| `/api/limit/query` (POL_002) | POST | 场景 9 保额上限 | 见下 `product-limit-pol002.json` |
| `/v1/risk/batch` | POST | 场景 10 基础风险 | 见下 `base-risk-resp.json` |
| `/v1/health/score` (×4) | POST | 场景 12 健康评分 | 按 insuredId 返回不同值 |
| `/v1/credit/report` (×2) | POST | 场景 13 征信查询 | 按 applicantId 返回不同值 |

### credit-score-resp.json

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "scores": [
      { "customerNo": "CUST_A_001", "score": 720, "level": "A", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_A_002", "score": 680, "level": "B", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_A_003", "score": 750, "level": "A", "scoreTime": "2026-05-20T10:00:00" },
      { "customerNo": "CUST_B_001", "score": 650, "level": "B", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_B_002", "score": 610, "level": "C", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_C_001", "score": 580, "level": "C", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_D_001", "score": 700, "level": "B", "scoreTime": "2026-05-15T10:00:00" },
      { "customerNo": "CUST_D_002", "score": 690, "level": "B", "scoreTime": "2026-05-15T10:00:00" }
    ],
    "orderRiskLevel": "LOW"
  }
}
```

### fraud-risk-resp.json

```json
{
  "code": 0,
  "data": {
    "riskScore": 35,
    "riskLevel": "LOW",
    "hitRules": ["RULE_FRAUD_BASIC"],
    "hitCustomerNos": []
  }
}
```

### occupation-risk-pol001.json

```json
{
  "code": 0,
  "data": {
    "occupationRisks": [
      { "customerNo": "CUST_A_001", "riskClass": 1, "riskDescription": "低风险职业" },
      { "customerNo": "CUST_B_001", "riskClass": 2, "riskDescription": "中低风险职业" }
    ]
  }
}
```

### occupation-risk-pol002.json

```json
{
  "code": 0,
  "data": {
    "occupationRisks": [
      { "customerNo": "CUST_C_001", "riskClass": 4, "riskDescription": "高风险职业" },
      { "customerNo": "CUST_D_001", "riskClass": 1, "riskDescription": "低风险职业" }
    ]
  }
}
```

### product-limit-pol001.json

```json
{
  "code": 0,
  "data": { "maxSumAssured": 2000000, "maxDailyPremium": 50000 }
}
```

### product-limit-pol002.json

```json
{
  "code": 0,
  "data": { "maxSumAssured": 500000, "maxDailyPremium": 10000 }
}
```

### base-risk-resp.json

```json
{
  "code": 0,
  "data": {
    "risks": [
      { "refId": "INS_001", "riskScore": 85, "fraudScore": 10, "amlFlag": false },
      { "refId": "INS_002", "riskScore": 72, "fraudScore": 25, "amlFlag": false },
      { "refId": "INS_003", "riskScore": 45, "fraudScore": 90, "amlFlag": true },
      { "refId": "INS_004", "riskScore": 68, "fraudScore": 15, "amlFlag": false }
    ]
  }
}
```

### health-score-ins001.json

```json
{ "code": 0, "data": { "healthScore": 95, "healthGrade": "EXCELLENT" } }
```

### health-score-ins002.json

```json
{ "code": 0, "data": { "healthScore": 82, "healthGrade": "GOOD" } }
```

### health-score-ins003.json

```json
{ "code": 0, "data": { "healthScore": 55, "healthGrade": "SUBSTANDARD" } }
```

### health-score-ins004.json

```json
{ "code": 0, "data": { "healthScore": 78, "healthGrade": "GOOD" } }
```

### credit-report-app001.json

```json
{ "code": 0, "data": { "creditScore": 780, "hasDefault": false, "loanCount": 3 } }
```

### credit-report-app002.json

```json
{ "code": 0, "data": { "creditScore": 620, "hasDefault": true, "loanCount": 8 } }
```

---

## 附：curl 测试命令

```bash
# 1. 直接核保评估（含特征取数 + 规则评估）
curl -X POST http://localhost:8080/api/underwriting/evaluate \
  -H 'Content-Type: application/json' \
  -d @docs/test-data/order.json

# 2. 仅特征取数（调试用）
curl -X POST http://localhost:8080/api/underwriting/extract \
  -H 'Content-Type: application/json' \
  -d @docs/test-data/order.json

# 3. 独立特征取数（指定特征码，含依赖展开）
curl -X POST http://localhost:8080/api/feature/extract \
  -H 'Content-Type: application/json' \
  -d @docs/test-data/feature-extract-request.json
```
