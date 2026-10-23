package com.stocksentinel.pullback;

import org.apache.ibatis.annotations.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PullbackMapper {
    class Candidate {
        public String symbol,name,version,details;public LocalDate dataDate;
        public BigDecimal averageAmount,referenceHigh;public OffsetDateTime lastBarAt,triggeredAt;
    }
    @Insert("INSERT INTO pullback_candidate(symbol,data_date,version,average_amount,reference_high,details) VALUES(#{symbol},#{dataDate},#{version},#{averageAmount},#{referenceHigh},CAST(#{details} AS jsonb)) ON CONFLICT DO NOTHING")
    int insert(Candidate row);
    @Select("SELECT p.*,s.name FROM pullback_candidate p JOIN stock_info s USING(symbol) WHERE p.data_date=#{date} AND p.version=#{version} AND p.triggered_at IS NULL AND s.status='ACTIVE' AND s.exchange IN ('SH','SZ') AND upper(s.name) NOT LIKE '%ST%' ORDER BY p.average_amount DESC,p.symbol LIMIT #{limit}")
    List<Candidate> active(@Param("date") LocalDate date,@Param("version") String version,@Param("limit") int limit);
    @Select("SELECT * FROM pullback_candidate WHERE symbol=#{symbol} AND data_date=#{dataDate} AND version=#{version} FOR UPDATE")
    Candidate lock(Candidate expected);
    @Update("UPDATE pullback_candidate SET last_bar_at=#{at},triggered_at=CASE WHEN #{triggered} THEN #{at} ELSE triggered_at END WHERE symbol=#{row.symbol} AND data_date=#{row.dataDate} AND version=#{row.version}")
    int advance(@Param("row") Candidate row,@Param("at") OffsetDateTime at,@Param("triggered") boolean triggered);
}
