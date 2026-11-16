package com.egg.archive;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 按好生意编码覆盖更新（hs_code 唯一），没有就新增。 */
    @Insert("INSERT INTO product (hs_code, name, unit_name, synced_at) VALUES (#{code}, #{name}, #{unit}, #{at}) AS v "
            + "ON DUPLICATE KEY UPDATE name = v.name, unit_name = v.unit_name, synced_at = v.synced_at")
    void upsert(@Param("code") String code, @Param("name") String name, @Param("unit") String unit,
            @Param("at") LocalDateTime at);
}
