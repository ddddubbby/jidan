package com.egg.chanjet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟好生意（开发规格 11.4，长期保留）。档案与原型 lab/web-sim.html 的演示数据一致：
 * 尾号 1234 对应李四与王秀英，尾号 5678 对应张三与另一位李四，用来走多人匹配。
 * 回传默认成功并返回假的单据 ID；两个失败开关（开发规格 11.4）让“下一次回传”被拒绝或没有答复，用一次就复位，
 * 用来走“回传失败 → 重新回传 / 退回待审核 / 标记为已回传”。收到的每一份报文都记下来，供测试核对“只收到一次”
 * 与“重传的报文与第一次完全相同”。
 *
 * 未经实物验证：没有接触过真实的好生意账套。
 */
public class FakeChanjetClient implements ChanjetClient {

    /** 下一次回传怎么失败。 */
    public enum Failure {
        /** 好生意明确拒绝，没有生成单据 */
        REJECT,
        /** 没收到答复，单据可能已经生成 */
        NO_REPLY
    }

    private static final int KEEP_SENT = 200;

    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicReference<Failure> failNext = new AtomicReference<>();
    private final List<String> sent = new ArrayList<>();

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

    /** 设置下一次回传的失败方式；传 null 取消。 */
    public void failNext(Failure failure) {
        failNext.set(failure);
    }

    public Failure pendingFailure() {
        return failNext.get();
    }

    /** 收到过的报文（含被拒绝、无答复的那几次），最新的在后；只留最近 200 份。 */
    public List<String> sentPayloads() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    @Override
    public String addPurchaseStockIn(String payload) {
        synchronized (sent) {
            sent.add(payload);
            if (sent.size() > KEEP_SENT) {
                sent.remove(0);
            }
        }
        Failure failure = failNext.getAndSet(null);
        if (failure == Failure.REJECT) {
            throw new ChanjetException.Rejected("模拟好生意：单据被拒绝（演示用的失败开关）");
        }
        if (failure == Failure.NO_REPLY) {
            throw new ChanjetException.NoReply("模拟好生意：没有答复（演示用的失败开关）");
        }
        return "FAKE-" + nextId.getAndIncrement();
    }

    @Override
    public String bookName() {
        return "鑫源蛋品 2026 账套";
    }
}
