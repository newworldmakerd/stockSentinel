package com.stocksentinel.stock;

import com.stocksentinel.common.entity.StockInfo;
import com.stocksentinel.common.entity.StockWatchlist;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WatchlistMapper {
    @Select("SELECT * FROM stock_info WHERE symbol=#{symbol}")
    StockInfo stock(String symbol);
    @Select("SELECT * FROM stock_info WHERE symbol LIKE concat(#{query},'%') OR name LIKE concat('%',#{query},'%') ORDER BY symbol LIMIT #{limit} OFFSET #{offset}")
    List<StockInfo> search(@Param("query") String query,@Param("limit") int limit,@Param("offset") int offset);
    @Insert("INSERT INTO stock_watchlist(symbol,remark) VALUES(#{symbol},#{remark}) ON CONFLICT(symbol) DO NOTHING")
    int add(@Param("symbol") String symbol,@Param("remark") String remark);
    @Select("SELECT * FROM stock_watchlist WHERE symbol=#{symbol}")
    StockWatchlist find(String symbol);
    @Select("SELECT * FROM stock_watchlist ORDER BY created_at DESC,id DESC")
    List<StockWatchlist> list();
    @Update("UPDATE stock_watchlist SET enabled=#{enabled},remark=#{remark} WHERE symbol=#{symbol}")
    int update(@Param("symbol") String symbol,@Param("enabled") boolean enabled,@Param("remark") String remark);
    @Delete("DELETE FROM stock_watchlist WHERE symbol=#{symbol}")
    int delete(String symbol);
}