-- Read-only independent recalculation of the deployed 2026-10-20 pullback rules.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '45s';
WITH eligible AS (
    SELECT i.symbol, s.name, i.average_amount,
        (i.details::jsonb #>> '{context,current,ma20}')::numeric AS ma20,
        (i.details::jsonb #>> '{context,previous,ma20}')::numeric AS prior_ma20
    FROM market_screen_item i JOIN stock_info s USING(symbol)
    WHERE i.trade_date=DATE '2026-10-21' AND i.details IS NOT NULL
), ranked AS (
    SELECT b.symbol,b.trade_date,b.close_price,b.high_price,b.volume,
        row_number() OVER (PARTITION BY b.symbol ORDER BY b.trade_date DESC) AS rn,
        lead(b.close_price) OVER (PARTITION BY b.symbol ORDER BY b.trade_date DESC) AS prior_close
    FROM market_daily_bar b JOIN eligible e USING(symbol)
    WHERE b.adjustment='QFQ' AND b.trade_date BETWEEN DATE '2025-10-20' AND DATE '2026-10-20'
), tail AS (
    SELECT symbol,count(*) AS bar_count,max(trade_date) AS last_date,
        min(rn) FILTER (WHERE prior_close IS NULL OR close_price>=prior_close)-1 AS days
    FROM ranked GROUP BY symbol
), measures AS (
    SELECT t.symbol,t.bar_count,t.last_date,t.days,
        max(b.close_price) FILTER (WHERE b.rn=1) AS close,
        max(b.close_price) FILTER (WHERE b.rn=t.days+1) AS anchor_close,
        max(b.close_price) FILTER (WHERE b.rn=t.days+21) AS base_close,
        max(b.high_price) FILTER (WHERE b.rn BETWEEN t.days+1 AND t.days+20) AS recent_high,
        sum(b.volume) FILTER (WHERE b.rn BETWEEN 1 AND t.days) AS pull_volume,
        sum(b.volume) FILTER (WHERE b.rn BETWEEN t.days+1 AND t.days+5) AS prior_volume,
        min(b.volume) FILTER (WHERE b.rn BETWEEN 1 AND t.days+5) AS minimum_volume
    FROM tail t JOIN ranked b USING(symbol)
    GROUP BY t.symbol,t.bar_count,t.last_date,t.days
), flags AS (
    SELECT e.*,m.bar_count,m.last_date,m.days,m.close,
        (m.anchor_close-m.base_close)*100/nullif(m.base_close,0) AS rise_pct,
        (m.recent_high-m.close)*100/nullif(m.recent_high,0) AS drawdown_pct,
        m.pull_volume*5/nullif(m.prior_volume*m.days,0) AS contraction,
        m.days BETWEEN 2 AND 5 AS days_ok,
        (m.anchor_close-m.base_close)*100>=m.base_close*15 AS rise_ok,
        (m.recent_high-m.close)*100 BETWEEN m.recent_high*3 AND m.recent_high*8 AS drawdown_ok,
        m.minimum_volume>0 AND m.pull_volume*5<=m.prior_volume*m.days*0.70 AS volume_ok,
        e.ma20>e.prior_ma20 AS ma_up_ok,
        m.close>e.ma20 AS above_ma_ok
    FROM measures m JOIN eligible e USING(symbol)
    WHERE m.bar_count>=27 AND m.last_date=DATE '2026-10-20'
), scored AS (
    SELECT *,coalesce(days_ok::int,0)+coalesce(rise_ok::int,0)+coalesce(drawdown_ok::int,0)
        +coalesce(volume_ok::int,0)+coalesce(ma_up_ok::int,0)+coalesce(above_ma_ok::int,0) AS passes
    FROM flags
)
SELECT jsonb_build_object(
    'counts',(SELECT jsonb_build_object(
        'evaluated',count(*),
        'days_2_to_5',count(*) FILTER (WHERE days_ok),
        'then_rise_15',count(*) FILTER (WHERE days_ok AND rise_ok),
        'then_drawdown_3_to_8',count(*) FILTER (WHERE days_ok AND rise_ok AND drawdown_ok),
        'then_volume_70pct',count(*) FILTER (WHERE days_ok AND rise_ok AND drawdown_ok AND volume_ok),
        'then_ma20_rising',count(*) FILTER (WHERE days_ok AND rise_ok AND drawdown_ok AND volume_ok AND ma_up_ok),
        'all_rules',count(*) FILTER (WHERE passes=6)) FROM scored),
    'matches',(SELECT coalesce(jsonb_agg(jsonb_build_object('symbol',symbol,'name',name,'days',days,
        'risePct',round(rise_pct,4),'drawdownPct',round(drawdown_pct,4),'volumeRatio',round(contraction,4),
        'close',close,'ma20',ma20,'priorMa20',prior_ma20)),'[]'::jsonb) FROM scored WHERE passes=6),
    'near_matches',(SELECT coalesce(jsonb_agg(x),'[]'::jsonb) FROM (
        SELECT symbol,name,days,round(rise_pct,4) AS rise_pct,round(drawdown_pct,4) AS drawdown_pct,
            round(contraction,4) AS volume_ratio,ma20,prior_ma20,close,
            array_remove(ARRAY[CASE WHEN NOT days_ok THEN 'DAYS' END,CASE WHEN NOT rise_ok THEN 'RISE' END,
                CASE WHEN NOT drawdown_ok THEN 'DRAWDOWN' END,CASE WHEN NOT volume_ok THEN 'VOLUME' END,
                CASE WHEN NOT ma_up_ok THEN 'MA_UP' END,CASE WHEN NOT above_ma_ok THEN 'ABOVE_MA' END],NULL) AS failed
        FROM scored WHERE days_ok AND passes=5 ORDER BY average_amount DESC,symbol LIMIT 5
    ) x)
);
ROLLBACK;
