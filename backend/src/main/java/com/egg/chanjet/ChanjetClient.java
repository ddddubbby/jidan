package com.egg.chanjet;

import java.util.List;

/**
 * 好生意（畅捷通）的唯一出口（开发规格 2.2、3.5）。真实实现在 P8-4，模拟实现是 {@link FakeChanjetClient}。
 * 出错一律抛 {@link ChanjetException}，message 是给人看的原因；回传时再区分“明确拒绝”与“没收到答复”。
 */
public interface ChanjetClient {

    /** 档案按好生意编码识别；字段只取本系统用得到的。 */
    record Product(String code, String name, String unitName) {
    }

    /** 供应商即农户，一个农户可有多个手机号。 */
    record Vendor(String code, String name, List<String> phones) {
    }

    record Customer(String code, String name) {
    }

    record Warehouse(String code, String name) {
    }

    List<Product> listProducts();

    List<Vendor> listVendors();

    List<Customer> listCustomers();

    List<Warehouse> listWarehouses();

    /**
     * 新增采购入库单，返回好生意单据 ID。payload 是审核时组装好的报文原文（JSON 文本），
     * 重新回传时原样再发，所以这里不解析、不改写。
     *
     * @throws ChanjetException.Rejected 好生意明确拒绝，message 为它返回的原始原因
     * @throws ChanjetException.NoReply  没收到答复，好生意侧可能已经生成了单据
     */
    String addPurchaseStockIn(String payload);

    /** 当前对接的账套名，给 Web 显示用。 */
    String bookName();
}
