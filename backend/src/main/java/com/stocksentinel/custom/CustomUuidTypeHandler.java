package com.stocksentinel.custom;
import org.apache.ibatis.type.*;import java.sql.*;import java.util.UUID;
public class CustomUuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override public void setNonNullParameter(PreparedStatement s,int index,UUID value,JdbcType type)throws SQLException{s.setObject(index,value);}
    @Override public UUID getNullableResult(ResultSet r,String column)throws SQLException{return r.getObject(column,UUID.class);}
    @Override public UUID getNullableResult(ResultSet r,int column)throws SQLException{return r.getObject(column,UUID.class);}
    @Override public UUID getNullableResult(CallableStatement s,int column)throws SQLException{return s.getObject(column,UUID.class);}
}
