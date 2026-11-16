package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 包装规格与标准净含量（W0）。 */
public class Spec {
    public Long id;
    public String name;
    public Integer eggCount;
    public BigDecimal stdNetKg;
    public Boolean enabled;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}
