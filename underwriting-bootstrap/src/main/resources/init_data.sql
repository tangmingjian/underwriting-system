-- ============================================================
-- 核保配置化系统 - 数据初始化脚本
-- 覆盖：STATIC / DIRECT / NACOS × ORDER / POLICY × INSURED / APPLICANT / POLICY / ORDER
-- 核心模式：同人客户号 — buildRequest 携带全部客户号, extractFeatures 按客户号反查合并
-- ============================================================

-- ============================================================
-- 场景 1：信用评分 — STATIC 多端点、ORDER 聚合、INSURED 存储
-- 查询：每人携带全部 customerNos 批量查
-- 合并：下游按 customerNo 返回 → 匹配回被保人/投保人
-- ============================================================

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status, description) VALUES
('buildCreditScoreReq', '信用评分-入参映射', 'INPUT',
'Map buildRequest(OrderFeatureContext ctx) {
    List<Map> persons = []

    // 只收集该特征相关产品下的被保人（每人带上全部客户号）
    ctx.getInsuredsForFeature("ins.creditScore").each { insCtx ->
        persons << [
            customerNos : insCtx.customerNos ?: [insCtx.insuredId],
            name        : insCtx.name,
            idNo        : insCtx.idNo,
            roleType    : "INSURED",
            refId       : insCtx.insuredId   // 用于回填匹配
        ]
    }

    // 只收集该特征相关产品下的投保人（每人带上全部客户号）
    ctx.getPoliciesForFeature("ins.creditScore").each { polCtx ->
        def app = polCtx.applicantCtx
        persons << [
            customerNos : app.customerNos ?: [app.applicantId],
            name        : app.name,
            idNo        : app.idNo,
            roleType    : "APPLICANT",
            refId       : app.applicantId
        ]
    }

    return [
        orderNo    : ctx.orderId,
        channelCode: ctx.channel ?: "ONLINE",
        queryType  : "CREDIT_SCORE",
        persons    : persons
    ]
}', 1, 'ACTIVE', '按特征筛选模式：只收集相关产品下的被保人和投保人'),

('extractCreditScore', '信用评分-出参提取', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, OrderFeatureContext ctx) {
    Map<String, Map<String, Object>> result = [:]

    // 下游返回结构：scores[i] = { customerNo, score, level }
    List scores = response?.data?.scores ?: []

    // 按 customerNo → 反查归属的被保人/投保人 → 合并特征
    scores.each { item ->
        String custNo   = item.customerNo as String
        int    score    = (item.score ?: 0) as int
        String level    = item.level ?: "C"
        String scoreTime = item.scoreTime ?: ""

        // 在相关被保人中查找拥有该客户号的
        InsuredFeatureContext insCtx = ctx.getInsuredsForFeature("ins.creditScore").find { ic ->
            (ic.customerNos ?: [ic.insuredId]).contains(custNo)
        }
        if (insCtx != null) {
            // 合并策略：如果已有结果，取最高分
            Map existing = result.get(insCtx.insuredId, [:])
            int existingScore = (existing["ins.creditScore"] ?: 0) as int
            if (score > existingScore) {
                result[insCtx.insuredId] = [
                    "ins.creditScore" : score,
                    "ins.creditLevel" : level,
                    "ins.creditCustNo": custNo,
                    "ins.creditTime"  : scoreTime ?: System.currentTimeMillis()
                ]
            }
            return  // groovy each closure continue
        }

        // 在相关投保人中查找
        ctx.getPoliciesForFeature("ins.creditScore").each { polCtx ->
            def app = polCtx.applicantCtx
            if ((app.customerNos ?: [app.applicantId]).contains(custNo)) {
                result[app.applicantId] = [
                    "app.creditScore" : score,
                    "app.creditLevel" : level,
                    "app.creditCustNo": custNo
                ]
            }
        }
    }

    // 订单级汇总风险等级
    if (response?.data?.orderRiskLevel != null) {
        result["__ORDER__"] = ["ord.creditRiskLevel": response.data.orderRiskLevel]
    }
    return result
}', 1, 'ACTIVE', '同人客户号模式：按 customerNo 反查被保人/投保人，合并多客户号结果（取最高分）');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ins.creditScore', '信用评分', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"STATIC","static_endpoints":["https://api.credit-center.gov.cn","https://api2.credit-center.gov.cn"],"protocol":"HTTPS","path":"/v2/credit/score","method":"POST","timeout_ms":5000,"headers":{"X-Api-Key":"${env.CREDIT_API_KEY}","X-Channel-Code":"INSURANCE_UW"}},"input_script_id":"buildCreditScoreReq","output_script_id":"extractCreditScore"}',
    'ORDER', 'INSURED', 1, 300, 'credit-center', 'zhangsan', 'DRAFT'
);

-- ============================================================
-- 场景 2：反欺诈风险 — NACOS、ORDER 聚合、ORDER 存储
-- 查询：订单下所有人 + 全部客户号
-- ============================================================

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status, description) VALUES
('buildFraudRiskReq', '反欺诈-入参映射', 'INPUT',
'Map buildRequest(OrderFeatureContext ctx) {
    List<Map> persons = []
    // 只收集该特征相关产品下的被保人
    ctx.getInsuredsForFeature("ord.fraudRiskScore").each { insCtx ->
        persons << [
            customerNos: insCtx.customerNos ?: [insCtx.insuredId],
            name       : insCtx.name,
            idNo       : insCtx.idNo,
            phone      : insCtx.phone ?: ""
        ]
    }
    // 只收集该特征相关产品下的投保人
    ctx.getPoliciesForFeature("ord.fraudRiskScore").each { polCtx ->
        def app = polCtx.applicantCtx
        persons << [
            customerNos: app.customerNos ?: [app.applicantId],
            name       : app.name,
            idNo       : app.idNo
        ]
    }
    return [
        orderNo  : ctx.orderId,
        channel  : ctx.channel,
        orderTime: ctx.order.orderTime?.toString(),
        persons  : persons
    ]
}', 1, 'ACTIVE', '按特征筛选模式：只收集相关产品下的人员'),

('extractFraudRisk', '反欺诈-出参提取', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, OrderFeatureContext ctx) {
    int    riskScore = (response?.data?.riskScore ?: 0) as int
    String riskLevel = response?.data?.riskLevel ?: "LOW"
    List   hitRules  = response?.data?.hitRules ?: []
    // 下游可能返回命中客户号列表
    List   hitCustNos = response?.data?.hitCustomerNos ?: []

    Map orderFeatures = [
        "ord.fraudRiskScore" : riskScore,
        "ord.fraudRiskLevel" : riskLevel,
        "ord.fraudHitRules"  : hitRules.join(","),
        "ord.fraudHitCustNos": hitCustNos.join(","),
        "ord.fraudTime"      : System.currentTimeMillis()
    ]
    return ["__ORDER__": orderFeatures]
}', 1, 'ACTIVE', '反欺诈结果直接写入订单级特征');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ord.fraudRiskScore', '反欺诈风险评分', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"NACOS","service_name":"risk-control-svc","namespace":"production","group":"DEFAULT_GROUP","protocol":"HTTP","path":"/api/v1/fraud/score","method":"POST","timeout_ms":2000,"headers":{"X-Source-System":"UNDERWRITING_ENGINE","X-Request-Id":"${ctx.traceId}"}},"input_script_id":"buildFraudRiskReq","output_script_id":"extractFraudRisk"}',
    'ORDER', 'ORDER', 1, 600, 'risk-control', 'lisi', 'DRAFT'
);

-- ============================================================
-- 场景 3：职业风险 — DIRECT、POLICY 聚合、INSURED 存储
-- 查询：保单下每个被保人的全部 customerNos
-- ============================================================

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status, description) VALUES
('buildOccupationRiskReq', '职业风险-入参映射', 'INPUT',
'Map buildRequest(PolicyFeatureContext polCtx) {
    List<Map> insureds = polCtx.insureds.collect { insCtx ->
        [
            insuredId   : insCtx.insuredId,
            customerNos : insCtx.customerNos ?: [insCtx.insuredId],
            idNo        : insCtx.idNo,
            occupation  : insCtx.occupation ?: "UNKNOWN",
            age         : insCtx.age
        ]
    }
    return [
        policyNo   : polCtx.policyId,
        productCode: polCtx.productCode,
        insureds   : insureds
    ]
}', 1, 'ACTIVE', '职业风险：每个被保人携带全部客户号'),

('extractOccupationRisk', '职业风险-出参提取', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, PolicyFeatureContext polCtx) {
    Map<String, Map<String, Object>> result = [:]

    // 下游返回结构：risks[i] = { customerNo, riskClass, riskDescription }
    List risks = response?.data?.occupationRisks ?: []

    risks.each { item ->
        String custNo    = item.customerNo as String
        int    riskClass = (item.riskClass ?: 1) as int
        String riskDesc  = item.riskDescription ?: ""

        // 反查该 customerNo 属于哪个被保人
        InsuredFeatureContext insCtx = polCtx.insureds.find { ic ->
            (ic.customerNos ?: [ic.insuredId]).contains(custNo)
        }
        if (insCtx != null) {
            // 合并策略：取最高风险等级
            Map existing = result.get(insCtx.insuredId, [:])
            int existClass = (existing["ins.occupationRiskClass"] ?: 0) as int
            if (riskClass > existClass) {
                result[insCtx.insuredId] = [
                    "ins.occupationRiskClass": riskClass,
                    "ins.occupationRiskDesc" : riskDesc,
                    "ins.occupationCustNo"   : custNo
                ]
            }
        }
    }
    return result
}', 1, 'ACTIVE', '按 customerNo 反查被保人，取最高风险等级');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'ins.occupationRisk', '职业风险等级', 'ATOMIC', 'INT', 'EXTERNAL_API',
    '{"service":{"discovery_type":"DIRECT","path":"https://occupation-risk.internal.api.com/v1/risk/evaluate","protocol":"HTTPS","method":"POST","timeout_ms":3000,"headers":{"Authorization":"Bearer ${env.OCCUPATION_API_TOKEN}"}},"input_script_id":"buildOccupationRiskReq","output_script_id":"extractOccupationRisk"}',
    'POLICY', 'INSURED', 1, 1800, 'occupation-risk', 'wangwu', 'DRAFT'
);

-- ============================================================
-- 场景 4：投保人收入核验 — STATIC 单端点、POLICY 聚合、APPLICANT 存储
-- 查询：投保人的全部 customerNos
-- ============================================================

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status, description) VALUES
('buildIncomeVerifyReq', '收入核验-入参映射', 'INPUT',
'Map buildRequest(PolicyFeatureContext polCtx) {
    def app = polCtx.applicantCtx
    return [
        policyNo       : polCtx.policyId,
        customerNos    : app.customerNos ?: [app.applicantId],
        idNo           : app.idNo,
        name           : app.name,
        declaredIncome : polCtx.policy.declaredIncome ?: 0,
        productCode    : polCtx.productCode
    ]
}', 1, 'ACTIVE', '收入核验：投保人全部客户号一次性查询'),

('extractIncomeVerify', '收入核验-出参提取', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, PolicyFeatureContext polCtx) {
    Map<String, Map<String, Object>> result = [:]

    def app = polCtx.applicantCtx

    // 下游可能对每个 customerNo 返回一份收入数据
    List incomeRecords = response?.data?.incomeRecords ?: []
    boolean allVerified = true
    double  maxAudited  = 0
    String  bestLevel   = "UNKNOWN"
    List    matchCustNos = []

    incomeRecords.each { rec ->
        String custNo    = rec.customerNo as String
        boolean verified = rec.verified ?: false
        double  audited  = (rec.auditedIncome ?: 0) as double
        String  level    = rec.incomeLevel ?: "UNKNOWN"

        // 确认该 customerNo 属于当前投保人
        if ((app.customerNos ?: [app.applicantId]).contains(custNo)) {
            matchCustNos << custNo
            if (!verified) allVerified = false
            if (audited > maxAudited) maxAudited = audited
            // 收入等级优先级：HIGH > MIDDLE > LOW > UNKNOWN
            if (level == "HIGH" || (level == "MIDDLE" && bestLevel != "HIGH")
                || (level == "LOW" && bestLevel == "UNKNOWN")) {
                bestLevel = level
            }
        }
    }

    result[app.applicantId] = [
        "app.incomeVerified"  : allVerified && !matchCustNos.isEmpty(),
        "app.auditedIncome"   : maxAudited,
        "app.incomeLevel"     : bestLevel,
        "app.incomeMatchCustNos": matchCustNos.join(",")
    ]
    return result
}', 1, 'ACTIVE', '多客户号收入结果合并：全验证通过才为true，取最高收入值');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'app.incomeVerified', '投保人收入核验', 'ATOMIC', 'BOOLEAN', 'EXTERNAL_API',
    '{"service":{"discovery_type":"STATIC","static_endpoints":["https://api.income-verify.gov.cn"],"protocol":"HTTPS","path":"/api/income/verify","method":"POST","timeout_ms":4000,"headers":{"X-Api-Key":"${env.INCOME_API_KEY}"}},"input_script_id":"buildIncomeVerifyReq","output_script_id":"extractIncomeVerify"}',
    'POLICY', 'APPLICANT', 1, 600, 'income-verify', 'zhaoliu', 'DRAFT'
);

-- ============================================================
-- 场景 5：产品最大保额查询 — DIRECT、POLICY 聚合、POLICY 存储
-- （不涉及 customerNos，纯保单级查询）
-- ============================================================

INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status, description) VALUES
('buildProductLimitReq', '产品保额-入参映射', 'INPUT',
'Map buildRequest(PolicyFeatureContext polCtx) {
    return [
        productCode   : polCtx.productCode,
        channel       : polCtx.orderContext?.channel ?: "ONLINE",
        appliedAmount : polCtx.policy.appliedAmount ?: 0
    ]
}', 1, 'ACTIVE', '保单级查询：不涉及 customerNos'),

('extractProductLimit', '产品保额-出参提取', 'OUTPUT',
'Map<String, Map<String, Object>> extractFeatures(Map response, PolicyFeatureContext polCtx) {
    double maxSumAssured   = (response?.data?.maxSumAssured ?: 0) as double
    double maxDailyPremium = (response?.data?.maxDailyPremium ?: 0) as double
    return [
        (polCtx.policyId): [
            "pol.maxSumAssured"   : maxSumAssured,
            "pol.maxDailyPremium" : maxDailyPremium
        ]
    ]
}', 1, 'ACTIVE', '产品保额限制直接存入保单特征');

INSERT INTO t_feature_config (
    feature_code, feature_name, category, data_type, calc_type,
    calc_config, aggregation, storage_level,
    version, ttl_seconds, source_system, owner, status
) VALUES (
    'pol.maxSumAssured', '产品最大保额', 'ATOMIC', 'DECIMAL', 'EXTERNAL_API',
    '{"service":{"discovery_type":"DIRECT","path":"https://product-engine.internal.api.com/api/limit/query","protocol":"HTTPS","method":"POST","timeout_ms":2000},"input_script_id":"buildProductLimitReq","output_script_id":"extractProductLimit"}',
    'POLICY', 'POLICY', 1, 3600, 'product-engine', 'sunqi', 'DRAFT'
);

-- ============================================================
-- 核保规则
-- ============================================================

-- 被保人信用评分 >= 600
INSERT INTO t_underwriting_rule (rule_code, rule_name, rule_type, expression, feature_codes, product_code, priority, status) VALUES
('RULE_CREDIT_001', '信用评分达标', 'INSURED',
 '#root[''ins.creditScore''] >= 600',
 'ins.creditScore', 'HEALTH_A_001', 10, 1);

-- 反欺诈风险评分 < 80
INSERT INTO t_underwriting_rule (rule_code, rule_name, rule_type, expression, feature_codes, product_code, priority, status) VALUES
('RULE_FRAUD_001', '反欺诈风险可控', 'INSURED',
 '#root[''ord.fraudRiskScore''] < 80',
 'ord.fraudRiskScore', 'HEALTH_A_001', 5, 1);

-- 职业风险等级 ≤ 3
INSERT INTO t_underwriting_rule (rule_code, rule_name, rule_type, expression, feature_codes, product_code, priority, status) VALUES
('RULE_OCCUPATION_001', '职业风险等级检查', 'INSURED',
 '#root[''ins.occupationRiskClass''] <= 3',
 'ins.occupationRisk', 'ACCIDENT_B_002', 20, 1);

-- 投保人收入核验必须通过
INSERT INTO t_underwriting_rule (rule_code, rule_name, rule_type, expression, feature_codes, product_code, priority, status) VALUES
('RULE_INCOME_001', '投保人收入核验', 'APPLICANT',
 '#root[''app.incomeVerified''] == true',
 'app.incomeVerified', 'HEALTH_A_001', 15, 1);

-- 保额上限检查
INSERT INTO t_underwriting_rule (rule_code, rule_name, rule_type, expression, feature_codes, product_code, priority, status) VALUES
('RULE_PRODUCT_001', '保额上限检查', 'POLICY',
 '#root[''pol.maxSumAssured''] > 0',
 'pol.maxSumAssured', 'ACCIDENT_B_002', 25, 1);

-- ============================================================
-- 同人客户号模式总结
-- ============================================================
--
-- 核心流程：
--   Person (Insured/Applicant)
--   ├── customerNos: ["CUST_A_001", "CUST_B_002", "CUST_C_003"]  ← 同一自然人的多个系统客户号
--   │
--   ▼ buildRequest
--   携带全部 customerNos 调用下游
--   request: { persons: [{ customerNos: [...], name, idNo, refId }, ...] }
--   │
--   ▼ extractFeatures (下游返回按 customerNo 粒度)
--   response: { scores: [{ customerNo: "CUST_A_001", score: 680 }, { customerNo: "CUST_B_002", score: 720 }, ...] }
--   │
--   ▼ 反查 + 合并
--   insCtx.customerNos.contains("CUST_A_001") → 归属 insCtx
--   insCtx.customerNos.contains("CUST_B_002") → 归属同一个 insCtx
--   → 合并策略：取 max(680, 720) = 720 → 归入该被保人
--
-- 合并策略（按特征语义）：
--   评分类 (creditScore) → 取最高分
--   风险类 (riskClass)   → 取最高风险
--   收入类 (income)      → 取最高收入，全验证通过才为 true
--   时间类 (timestamp)   → 取最新时间
