package com.stocksentinel;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+")
class TTradeMigrationLocalDatabaseIT {
    @Test void upgradeFromV17PreservesLegacyAggregateAndRejectsMissingTradeValues() throws Exception {
        String schema="stocksentinel_upgrade_"+UUID.randomUUID().toString().replace("-","");
        if(!schema.matches("stocksentinel_upgrade_[a-f0-9]{32}"))throw new IllegalStateException();
        String url=System.getenv("STOCKSENTINEL_TEST_DB_URL")+"?currentSchema="+schema,user=System.getenv("STOCKSENTINEL_TEST_DB_USER"),password=System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD");
        try{
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("17").load().migrate();
            try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
                s.execute("INSERT INTO stock_info(symbol,name,exchange) VALUES('000731','测试股票','SZ')");
                s.execute("INSERT INTO t_observation_day(group_hash,user_id,symbol,trade_date,position_revision,baseline_quantity,baseline_sellable,sell_filled,sell_price) VALUES('test-scope','test-user','000731','2026-10-21',1,800,800,200,8)");
                s.execute("INSERT INTO t_observation_receipt(message_hash,group_hash,user_id,symbol,trade_date,side,quantity,price,recorded_at) VALUES(repeat('a',64),'test-scope','test-user','000731','2026-10-21','SELL',200,8,'2026-10-21 10:30:00+08')");
            }
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
            try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
                try(var r=s.executeQuery("SELECT count(*) FROM t_trade_event WHERE origin='LEGACY_AGGREGATE' AND quantity=200 AND price=8 AND created_at=TIMESTAMPTZ '2026-10-21 10:30:00+08'")){r.next();assertEquals(1,r.getInt(1));}
                try(var r=s.executeQuery("SELECT sell_filled FROM t_observation_day")){r.next();assertEquals(200,r.getLong(1));}
                assertThrows(java.sql.SQLException.class,()->s.execute("INSERT INTO t_trade_event(message_hash,group_hash,user_id,symbol,trade_date,kind,side,quantity,price) VALUES(repeat('b',64),'test-scope','test-user','000731','2026-10-21','FILL','SELL',NULL,NULL)"));
            }
        }finally{
            if(!schema.matches("stocksentinel_upgrade_[a-f0-9]{32}"))throw new IllegalStateException();
            try(var c=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),user,password);var s=c.createStatement()){s.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}
        }
    }
}
