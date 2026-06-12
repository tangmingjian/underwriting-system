-- =============================================
-- 核保配置化系统 - 数据库初始化脚本
-- =============================================

-- 特征配置表
CREATE TABLE IF NOT EXISTS t_feature_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_code    VARCHAR(50)   NOT NULL UNIQUE COMMENT '特征码，如 ins.healthScore',
    feature_name    VARCHAR(100)  COMMENT '特征名称',
    category        ENUM('ATOMIC','DERIVED','COMPOSITE') NOT NULL DEFAULT 'ATOMIC' COMMENT '特征分类',
    data_type       ENUM('INT','DECIMAL','STRING','BOOLEAN','JSON','ARRAY') NOT NULL DEFAULT 'STRING' COMMENT '特征值数据类型',
    calc_type       ENUM('PARAM_MAPPING','EXPRESSION','EXTERNAL_API','DATABASE_QUERY','COMPOSITE') NOT NULL DEFAULT 'EXTERNAL_API' COMMENT '计算类型',
    calc_config     JSON          NOT NULL COMMENT '计算配置（根据calc_type存储不同结构）',
    aggregation     VARCHAR(20)   NOT NULL DEFAULT 'POLICY' COMMENT '聚合级别: ORDER / POLICY / INSURED / APPLICANT',
    storage_level   VARCHAR(20)   NOT NULL DEFAULT 'INSURED' COMMENT '存储级别: INSURED / APPLICANT / POLICY / ORDER',
    version         INT           DEFAULT 1 COMMENT '配置版本号',
    default_value   VARCHAR(256)  COMMENT '默认值',
    ttl_seconds     INT           DEFAULT 300 COMMENT '特征值缓存TTL（秒）',
    source_system   VARCHAR(64)   COMMENT '数据来源系统',
    owner           VARCHAR(64)   COMMENT '负责人',
    status          ENUM('DRAFT','ACTIVE','DEPRECATED') DEFAULT 'DRAFT' COMMENT '状态',
    extra_params    VARCHAR(500)  COMMENT '扩展参数JSON',
    depends_on      JSON          COMMENT '依赖的特征码列表，如 ["ins.B", "pol.C"]',
    created_date     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_date     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 特征脚本表（出入参映射 Groovy 脚本，独立版本管理）
CREATE TABLE IF NOT EXISTS t_feature_script (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id   VARCHAR(100) NOT NULL COMMENT '脚本标识（calc_config中引用的input_script_id / output_script_id）',
    script_name VARCHAR(200) COMMENT '脚本名称',
    script_type ENUM('INPUT','OUTPUT','EXPRESSION') NOT NULL COMMENT '脚本类型: INPUT=入参映射, OUTPUT=出参映射, EXPRESSION=表达式计算',
    script_text MEDIUMTEXT   NOT NULL COMMENT 'Groovy脚本全文',
    version     INT          DEFAULT 1 COMMENT '版本号',
    status      ENUM('DRAFT','ACTIVE','DEPRECATED') DEFAULT 'DRAFT' COMMENT '状态',
    description VARCHAR(500) COMMENT '脚本说明',
    created_date DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_date DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_script_version (script_id, version)
);

-- 核保规则表
CREATE TABLE IF NOT EXISTS t_underwriting_rule (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_code     VARCHAR(50)   NOT NULL UNIQUE COMMENT '规则编码',
    rule_name     VARCHAR(100)  COMMENT '规则名称',
    rule_type     VARCHAR(20)   NOT NULL COMMENT '规则类型: INSURED / APPLICANT / POLICY / ORDER',
    eval_type     VARCHAR(30)   DEFAULT 'CONDITION_LIST' COMMENT '评估类型: CONDITION_LIST / CROSS_DECISION_TABLE / SCORECARD',
    expression    VARCHAR(1000) NOT NULL COMMENT '规则JSON DSL配置',
    feature_codes VARCHAR(500)  COMMENT '依赖的特征码，逗号分隔',
    product_code  VARCHAR(50)   COMMENT '适用产品码，关联 t_feature_config 中的产品',
    priority      INT           DEFAULT 0,
    status        TINYINT       DEFAULT 1 COMMENT '1:启用 0:停用',
    version       INT           DEFAULT 1 COMMENT '当前版本号',
    wording_config TEXT          COMMENT '话素配置JSON: {"A":{"pass":"...","fail":"..."},"B":{...},"C":{...}}',
    created_date   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_date   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 核保规则历史归档表
CREATE TABLE IF NOT EXISTS t_underwriting_rule_history (
    history_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    id            BIGINT        COMMENT '原规则ID',
    rule_code     VARCHAR(50)   NOT NULL COMMENT '规则编码',
    rule_name     VARCHAR(100)  COMMENT '规则名称',
    rule_type     VARCHAR(20)   NOT NULL COMMENT '规则类型: INSURED / APPLICANT / POLICY / ORDER',
    eval_type     VARCHAR(30)   COMMENT '评估类型',
    expression    VARCHAR(1000) NOT NULL COMMENT '规则JSON DSL配置',
    feature_codes VARCHAR(500)  COMMENT '依赖的特征码，逗号分隔',
    product_code  VARCHAR(50)   COMMENT '适用产品码',
    priority      INT           DEFAULT 0,
    status        TINYINT       DEFAULT 1 COMMENT '1:启用 0:停用',
    version       INT           DEFAULT 1 COMMENT '版本号',
    wording_config TEXT          COMMENT '话素配置JSON快照',
    change_type   VARCHAR(20)   NOT NULL COMMENT '变更类型: CREATE / UPDATE / DELETE',
    changed_at    DATETIME      NOT NULL COMMENT '归档时间',
    created_date   DATETIME      COMMENT '原记录创建时间',
    updated_date   DATETIME      COMMENT '原记录更新时间',
    INDEX idx_rule_history_code (rule_code),
    INDEX idx_rule_history_rule_id (id)
);

-- 交叉决策表
CREATE TABLE IF NOT EXISTS t_cross_decision_table (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_code      VARCHAR(50)   NOT NULL UNIQUE COMMENT '决策表编码',
    table_name      VARCHAR(100)  COMMENT '决策表名称',
    row_feature     VARCHAR(100)  COMMENT '行特征码',
    col_feature     VARCHAR(100)  COMMENT '列特征码',
    cells           JSON          COMMENT '单元格JSON: [{"row":"val","col":"val","result":true}]',
    default_result  TINYINT       DEFAULT 0 COMMENT '默认结果: 1=true 0=false',
    status          TINYINT       DEFAULT 1 COMMENT '1:启用 0:停用',
    created_date     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_date     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 评分卡配置表
CREATE TABLE IF NOT EXISTS t_scorecard_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    scorecard_code  VARCHAR(50)   NOT NULL UNIQUE COMMENT '评分卡编码',
    scorecard_name  VARCHAR(100)  COMMENT '评分卡名称',
    dimensions      JSON          COMMENT '评分维度JSON',
    scoring_formula VARCHAR(500)  COMMENT '评分公式，如 "{dim1}*0.6+{dim2}*0.4"',
    buckets         JSON          COMMENT '分桶JSON: [{"min":0,"max":60,"result":false}]',
    status          TINYINT       DEFAULT 1 COMMENT '1:启用 0:停用',
    created_date     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_date     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- =============================================
-- 三种服务发现 calc_config 配置示例
-- =============================================

-- ### STATIC 多端点 ###
-- {
--   "service": {
--     "discovery_type": "STATIC",
--     "static_endpoints": ["https://api.credit-center.gov.cn", "https://api2.credit-center.gov.cn"],
--     "protocol": "HTTPS", "path": "/v2/credit/score",
--     "method": "POST", "timeout_ms": 5000,
--     "headers": { "X-Api-Key": "${env.CREDIT_API_KEY}", "X-Channel-Code": "INSURANCE_UW" }
--   },
--   "input_script_id": "buildCreditScoreRequest",
--   "output_script_id": "extractCreditScore"
-- }

-- ### DIRECT 直连 ###
-- { "service": { "discovery_type": "DIRECT", "path": "https://api.internal.example.com/v1/score", ... }, ... }

-- ### NACOS 服务发现 ###
-- { "service": { "discovery_type": "NACOS", "service_name": "risk-control-svc", "namespace": "production", ... }, ... }

-- =============================================
-- 完整 INSERT 示例
-- =============================================

-- -- 1. 插入脚本（语义化字段名，Handler 用 featureCode 包裹）
-- INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status)
-- VALUES ('buildCreditScoreRequest', '信用评分入参映射', 'INPUT',
-- 'Map buildRequest(OrderFeatureContext ctx) {
--     List<Map> persons = []
--     ctx.getInsuredsForFeature("ins.creditScore").each { insCtx ->
--         persons << [customerNos: insCtx.customerNos ?: [insCtx.insuredId], name: insCtx.name, idNo: insCtx.idNo, refId: insCtx.insuredId]
--     }
--     return [orderNo: ctx.orderId, channelCode: ctx.channel ?: "ONLINE", persons: persons]
-- }', 1, 'ACTIVE');
--
-- INSERT INTO t_feature_script (script_id, script_name, script_type, script_text, version, status)
-- VALUES ('extractCreditScore', '信用评分出参提取', 'OUTPUT',
-- 'Map<String, Map<String, Object>> extractFeatures(Map response, OrderFeatureContext ctx) {
--     Map<String, Map<String, Object>> result = [:]
--     List scores = response?.data?.scores ?: []
--     scores.each { item ->
--         String refId = item.refId as String
--         result[refId] = ["score": (item.score ?: 0) as int, "level": item.level ?: "C"]
--     }
--     return result
-- }', 1, 'ACTIVE');
--
-- -- 2. 插入特征配置（通过 calc_config 引用脚本 ID）
-- INSERT INTO t_feature_config (
--     feature_code, feature_name, category, data_type, calc_type,
--     calc_config, aggregation, storage_level, version, ttl_seconds, source_system, owner, status
-- ) VALUES (
--     'ins.creditScore', '信用评分', 'ATOMIC', 'INT', 'EXTERNAL_API',
--     '{"service":{"discovery_type":"STATIC","static_endpoints":["https://api.credit-center.gov.cn","https://api2.credit-center.gov.cn"],"protocol":"HTTPS","path":"/v2/credit/score","method":"POST","timeout_ms":5000,"headers":{"X-Api-Key":"${env.CREDIT_API_KEY}","X-Channel-Code":"INSURANCE_UW"}},"input_script_id":"buildCreditScoreRequest","output_script_id":"extractCreditScore"}',
--     'ORDER', 'INSURED', 1, 300, 'credit-center', 'zhangsan', 'DRAFT'
-- );
