package com.egg.order;

/** 单据状态只存这四种（开发规格 3.4）；“回传中”是界面状态，不入库。 */
public enum OrderStatus {
    PENDING, APPROVED, PUSHED, VOID
}
