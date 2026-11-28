package com.egg.order;

import java.math.BigDecimal;

/**
 * 逐箱流水。seq 就是箱贴二维码里的批次序号。
 * groupNo / groupK：同一次合并称重的几箱共用一个称重组号，k 是那次称的箱数。
 */
public class WeighRecord {
    public Long id;
    public Long orderId;
    public Integer seq;
    public BigDecimal netJin;
    /** 手录的箱没有毛重 */
    public BigDecimal grossJin;
    public BigDecimal tareJin;
    public String tareName;
    public Integer groupNo;
    public Integer groupK;
    public Boolean manual;
    /** 手录前秤给出的净重；手工加的箱与 Web 自建单为空 */
    public BigDecimal rawJin;
    public Boolean printed;
}
