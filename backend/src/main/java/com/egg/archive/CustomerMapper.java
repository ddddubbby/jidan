package com.egg.archive;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {

    @Insert("INSERT INTO customer (hs_code, name, synced_at) VALUES (#{code}, #{name}, #{at}) AS v "
            + "ON DUPLICATE KEY UPDATE name = v.name, synced_at = v.synced_at")
    void upsert(@Param("code") String code, @Param("name") String name, @Param("at") LocalDateTime at);
}
