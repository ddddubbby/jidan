package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BasePriceMapper extends BaseMapper<BasePrice> {

    /** 同一天同一规格重复录入是覆盖：靠（price_date, spec_id）唯一键，一条语句、不先查后写。 */
    @Insert("INSERT INTO base_price (price_date, spec_id, base_price, updated_by, updated_at) "
            + "VALUES (#{date}, #{specId}, #{price}, #{by}, #{at}) AS v "
            + "ON DUPLICATE KEY UPDATE base_price = v.base_price, updated_by = v.updated_by, updated_at = v.updated_at")
    void upsert(@Param("date") LocalDate date, @Param("specId") long specId, @Param("price") BigDecimal price,
            @Param("by") Long by, @Param("at") LocalDateTime at);
}
