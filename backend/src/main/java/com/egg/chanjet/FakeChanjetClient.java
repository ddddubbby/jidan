package com.egg.chanjet;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟好生意（开发规格 11.4，长期保留）。档案与原型 lab/web-sim.html 的演示数据一致：
 * 尾号 1234 对应李四与王秀英，尾号 5678 对应张三与另一位李四，用来走多人匹配。
 * 回传默认成功并返回假的单据 ID；“下一次回传被拒绝 / 无答复”两个开关随回传任务（P2-4）一起做。
 *
 * 未经实物验证：没有接触过真实的好生意账套。
 */
public class FakeChanjetClient implements ChanjetClient {

    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public List<Product> listProducts() {
        return List.of(new Product("SP0001", "粉蛋", "箱"), new Product("SP0002", "红蛋", "箱"));
    }

    @Override
    public List<Vendor> listVendors() {
        return List.of(
                new Vendor("GYS0001", "李四", List.of("13603511234")),
                new Vendor("GYS0002", "王秀英", List.of("15803511234", "13935107788")),
                new Vendor("GYS0003", "张三", List.of("13800005678")),
                new Vendor("GYS0004", "李四", List.of("13099995678")));
    }

    @Override
    public List<Customer> listCustomers() {
        return List.of(new Customer("KH0001", "郑州永丰农产品批发市场"), new Customer("KH0002", "北京新发地蛋品行"));
    }

    @Override
    public List<Warehouse> listWarehouses() {
        return List.of(new Warehouse("CK01", "1号仓库"), new Warehouse("CK02", "2号仓库"));
    }

    @Override
    public String addPurchaseStockIn(String payload) {
        return "FAKE-" + nextId.getAndIncrement();
    }

    @Override
    public String bookName() {
        return "鑫源蛋品 2026 账套";
    }
}
