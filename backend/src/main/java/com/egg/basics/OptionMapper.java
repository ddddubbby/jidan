package com.egg.basics;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OptionMapper extends BaseMapper<Option> {

    /** 有就覆盖，没有就新增（opt_key 唯一）。 */
    @Insert("INSERT INTO `option` (opt_key, opt_value, updated_by, updated_at) "
            + "VALUES (#{key}, #{value}, #{by}, #{at}) AS v "
            + "ON DUPLICATE KEY UPDATE opt_value = v.opt_value, updated_by = v.updated_by, updated_at = v.updated_at")
    void upsert(@Param("key") String key, @Param("value") String value, @Param("by") Long by,
            @Param("at") LocalDateTime at);
}
