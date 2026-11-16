package com.egg.basics;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

/** option 表的一行：只存被改过的值，没存过的取注册表里的默认值。`option` 是 MySQL 保留字，要加反引号。 */
@TableName("`option`")
public class Option {
    public Long id;
    public String optKey;
    public String optValue;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}
