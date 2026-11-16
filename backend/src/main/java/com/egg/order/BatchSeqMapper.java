package com.egg.order;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BatchSeqMapper {

    /** 当日第一次取号插入 1，之后加 1。一条语句，靠主键的行锁保证并发时不重号。 */
    @Insert("INSERT INTO batch_seq (biz_date, last_seq) VALUES (#{date}, 1) "
            + "ON DUPLICATE KEY UPDATE last_seq = last_seq + 1")
    void bump(@Param("date") LocalDate date);

    /** 必须在 {@link #bump} 的同一个事务里读：行锁还在，读到的就是自己刚加的那个数。 */
    @Select("SELECT last_seq FROM batch_seq WHERE biz_date = #{date}")
    int last(@Param("date") LocalDate date);
}
