-- 影子股票目录：稳定展示身份、自动发现生命周期与 56 支首发 bStock。
-- 真实 symbol 永不改名；展示别名与上游身份分层保存，避免历史持仓/订单断链。
BEGIN;

ALTER TABLE bstock ADD COLUMN IF NOT EXISTS display_name VARCHAR(64);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS display_code VARCHAR(16);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS display_lore VARCHAR(255);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS alias_source VARCHAR(16) NOT NULL DEFAULT 'RULE';
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS alias_version INT NOT NULL DEFAULT 1;
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS alias_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS source_chain_id VARCHAR(32);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS source_contract_address VARCHAR(128);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS source_token_symbol VARCHAR(32);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS source_icon_url VARCHAR(512);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS catalog_status VARCHAR(16) NOT NULL DEFAULT 'LISTED';
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS source_status VARCHAR(32) NOT NULL DEFAULT 'TRADING';
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS underlying_status VARCHAR(64);
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS missing_sync_count INT NOT NULL DEFAULT 0;
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMP;
ALTER TABLE bstock ADD COLUMN IF NOT EXISTS metadata_synced_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_bstock_catalog_status ON bstock(catalog_status, source_status, sort);
CREATE INDEX IF NOT EXISTS idx_bstock_last_seen ON bstock(last_seen_at);

-- 兼容旧后台曾经手工 enabled=false 的条目；升级不能把运营下架悄悄改回上架。
UPDATE bstock
SET catalog_status = 'RETIRED'
WHERE enabled = FALSE
  AND catalog_status = 'LISTED';

-- 2026-07-30 已核验的 Binance Spot 直连 bStock 首发目录。
-- 这里只固定“首发宇宙”，不固定展示名；别名由版本化算法首次生成后落库冻结。
INSERT INTO bstock (symbol, ticker, name, enabled, sort, catalog_status, source_status, first_seen_at, last_seen_at)
SELECT v.symbol, v.ticker, v.ticker, TRUE, v.sort, 'LISTED', 'TRADING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (VALUES
    ('AAOIBUSDT','AAOI',101), ('AAPLBUSDT','AAPL',102), ('AMATBUSDT','AMAT',103),
    ('AMDBUSDT','AMD',7), ('AMZNBUSDT','AMZN',104), ('ARMBUSDT','ARM',105),
    ('AVGOBUSDT','AVGO',106), ('AXTIBUSDT','AXTI',107), ('BABABUSDT','BABA',108),
    ('BEBUSDT','BE',109), ('CBRSBUSDT','CBRS',110), ('COINBUSDT','COIN',111),
    ('CRCLBUSDT','CRCL',5), ('CRWVBUSDT','CRWV',112), ('DELLBUSDT','DELL',113),
    ('DRAMBUSDT','DRAM',114), ('EWYBUSDT','EWY',115), ('FLNCBUSDT','FLNC',116),
    ('GLWBUSDT','GLW',117), ('GOOGLBUSDT','GOOGL',118), ('GSBUSDT','GS',119),
    ('HOODBUSDT','HOOD',120), ('IBMBUSDT','IBM',121), ('INTCBUSDT','INTC',122),
    ('INTWBUSDT','INTW',123), ('KORUBUSDT','KORU',124), ('LITEBUSDT','LITE',125),
    ('METABUSDT','META',126), ('MRVLBUSDT','MRVL',127), ('MSFTBUSDT','MSFT',128),
    ('MSTRBUSDT','MSTR',6), ('MUBUSDT','MU',3), ('MUUBUSDT','MUU',129),
    ('MVLLBUSDT','MVLL',130), ('NBISBUSDT','NBIS',131), ('NOKBUSDT','NOK',132),
    ('NVDABUSDT','NVDA',1), ('ORCLBUSDT','ORCL',133), ('PLTRBUSDT','PLTR',134),
    ('PYPLBUSDT','PYPL',135), ('QCOMBUSDT','QCOM',136), ('QNTBUSDT','QNT',137),
    ('QQQBUSDT','QQQ',9), ('RKLBBUSDT','RKLB',138), ('SKHYBUSDT','SKHY',139),
    ('SMHBUSDT','SMH',140), ('SNDKBUSDT','SNDK',4), ('SNXXBUSDT','SNXX',141),
    ('SOXLBUSDT','SOXL',10), ('SOXSBUSDT','SOXS',142), ('SPCXBUSDT','SPCX',8),
    ('SPYBUSDT','SPY',143), ('TQQQBUSDT','TQQQ',144), ('TSLABUSDT','TSLA',2),
    ('TSMBUSDT','TSM',145), ('WDCBUSDT','WDC',146)
) AS v(symbol, ticker, sort)
ON CONFLICT (symbol) DO UPDATE SET
    ticker = EXCLUDED.ticker,
    last_seen_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP;

UPDATE bstock
SET catalog_status = COALESCE(NULLIF(catalog_status, ''), CASE WHEN enabled THEN 'LISTED' ELSE 'RETIRED' END),
    source_status = COALESCE(NULLIF(source_status, ''), 'TRADING'),
    first_seen_at = COALESCE(first_seen_at, created_at),
    updated_at = CURRENT_TIMESTAMP;

COMMENT ON COLUMN bstock.display_name IS '影子市场稳定展示名；自动生成一次后持久化，管理员可覆盖';
COMMENT ON COLUMN bstock.catalog_status IS 'CANDIDATE/LISTED/PAUSED/RETIRED，独立于上游状态';
COMMENT ON COLUMN bstock.source_status IS 'Binance Spot 数据源状态；买入需为 TRADING';

COMMIT;
