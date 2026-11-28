package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 包装箱皮重与热键（hotkey：称重页是否把它显示成数字皮热键）。 */
public class TareBox {
    public Long id;
    public String name;
    public BigDecimal tareJin;
    public Boolean hotkey;
    public Boolean enabled;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}
