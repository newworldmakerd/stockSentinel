package com.stocksentinel.advisory;

import org.apache.ibatis.annotations.*;

@Mapper
public interface AdviceStore {
    @Select("SELECT jsonb_build_object('signal',to_jsonb(s),'ruleHits',COALESCE((SELECT jsonb_agg(to_jsonb(h)) FROM signal_rule_hit h WHERE h.signal_id=s.id),'[]'::jsonb))::text FROM signal_event s WHERE s.id=#{id}")
    String loadVerifiedSignal(long id);

    @Select("SELECT count(*) FROM signal_advice WHERE signal_id=#{id} AND provider='OPENAI_COMPATIBLE' AND model=#{model} AND prompt_version=#{version}")
    int existing(@Param("id") long id, @Param("model") String model, @Param("version") String version);

    @Insert("INSERT INTO signal_advice(signal_id,provider,model,prompt_version,status,input_snapshot,advice,error_code) VALUES(#{id},'OPENAI_COMPATIBLE',#{model},#{version},#{status},CAST(#{input} AS jsonb),CAST(#{advice} AS jsonb),#{error}) ON CONFLICT(signal_id,provider,model,prompt_version) DO NOTHING")
    int save(@Param("id") long id, @Param("model") String model, @Param("version") String version,
             @Param("status") String status, @Param("input") String input,
             @Param("advice") String advice, @Param("error") String error);
}