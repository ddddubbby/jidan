package com.egg.archive;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VendorMapper extends BaseMapper<Vendor> {

    @Insert("INSERT INTO vendor (hs_code, name, synced_at) VALUES (#{code}, #{name}, #{at}) AS v "
            + "ON DUPLICATE KEY UPDATE name = v.name, synced_at = v.synced_at")
    void upsert(@Param("code") String code, @Param("name") String name, @Param("at") LocalDateTime at);

    /**
     * 某位磅秤员最近收过的农户，最近的在前。作废的单不算收过；
     * 按单据 id 排而不是按时间，同一秒建的两张单也有确定的先后。
     */
    @Select("SELECT v.* FROM vendor v JOIN ("
            + "SELECT vendor_id, MAX(id) AS last_id FROM purchase_order "
            + "WHERE operator_id = #{operatorId} AND status <> 'VOID' GROUP BY vendor_id "
            + "ORDER BY last_id DESC LIMIT #{limit}) r ON r.vendor_id = v.id ORDER BY r.last_id DESC")
    List<Vendor> recentOf(@Param("operatorId") long operatorId, @Param("limit") int limit);
}
