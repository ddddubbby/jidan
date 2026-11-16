package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每规格每日一条的市场基准价（M0，元/箱）。 */
public class BasePrice {
    public Long id;
    public LocalDate priceDate;
    public Long specId;
    public BigDecimal basePrice;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}
