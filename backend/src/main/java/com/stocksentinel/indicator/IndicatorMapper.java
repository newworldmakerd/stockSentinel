package com.stocksentinel.indicator;

import com.stocksentinel.common.entity.MarketDailyBar;
import com.stocksentinel.common.entity.MarketIndicatorDaily;
import com.stocksentinel.market.MarketContracts.Adjustment;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IndicatorMapper {
    @Select("SELECT * FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment} AND trade_date <= #{asOf} ORDER BY trade_date LIMIT 10001")
    List<MarketDailyBar> history(@Param("symbol") String symbol, @Param("adjustment") Adjustment adjustment,
                                @Param("asOf") LocalDate asOf);

    @Insert("""
        <script>
        INSERT INTO market_indicator_daily(symbol,trade_date,ma5,ma10,ma20,ma60,dif,dea,macd_hist,
            volume_ratio,calculation_version,adjustment) VALUES
        <foreach collection="rows" item="r" separator=",">
          (#{symbol},#{r.tradeDate},#{r.ma5},#{r.ma10},#{r.ma20},#{r.ma60},#{r.dif},#{r.dea},#{r.macdHist},
           #{r.volumeRatio},#{version},#{adjustment})
        </foreach>
        ON CONFLICT(symbol,trade_date,calculation_version,adjustment) DO UPDATE SET
          ma5=EXCLUDED.ma5,ma10=EXCLUDED.ma10,ma20=EXCLUDED.ma20,ma60=EXCLUDED.ma60,
          dif=EXCLUDED.dif,dea=EXCLUDED.dea,macd_hist=EXCLUDED.macd_hist,
          volume_ratio=EXCLUDED.volume_ratio,calculated_at=now()
        </script>
        """)
    int upsert(@Param("symbol") String symbol,@Param("adjustment") Adjustment adjustment,
               @Param("version") String version,@Param("rows") List<IndicatorContracts.Row> rows);

    @Select("SELECT * FROM market_indicator_daily WHERE symbol=#{symbol} AND adjustment=#{adjustment} AND calculation_version=#{version} AND trade_date <= #{asOf} ORDER BY trade_date DESC LIMIT 2")
    List<MarketIndicatorDaily> latest(@Param("symbol") String symbol,@Param("adjustment") Adjustment adjustment,
        @Param("version") String version,@Param("asOf") LocalDate asOf);
}