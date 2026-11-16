package com.egg.order;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 批次号与单据编号的格式，全系统只在这里拼、这里拆（v7 5.5、开发规格 5.1）。
 * 批次 {@code SG20261115-2} 对应单据编号 {@code CGRK20261115002}。
 */
public final class BatchNo {

    public static final String PATTERN = "SG\\d{8}-[1-9]\\d*";

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private BatchNo() {
    }

    public static String of(LocalDate date, int seq) {
        return "SG" + date.format(COMPACT) + "-" + seq;
    }

    /** 批次号里的日期，即收购日期。 */
    public static LocalDate dateOf(String batchNo) {
        return LocalDate.parse(batchNo.substring(2, 10), COMPACT);
    }

    public static String codeOf(String batchNo) {
        int dash = batchNo.indexOf('-');
        return "CGRK" + batchNo.substring(2, dash) + String.format("%03d", Integer.parseInt(batchNo.substring(dash + 1)));
    }
}
