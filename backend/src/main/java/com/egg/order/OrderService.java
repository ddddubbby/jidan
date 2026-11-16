package com.egg.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.archive.ArchiveService;
import com.egg.basics.OptionService;
import com.egg.basics.SpecService;
import com.egg.common.ApiException;
import com.egg.common.CurrentUser;
import com.egg.common.ErrorCode;
import com.egg.common.Role;
import com.egg.pricing.PricingService;
import com.egg.pricing.PricingService.Result;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 单据的提交与（后续任务加入的）状态流转。防重只有一条：{@code batch_no} 唯一，
 * 同一批次号再次提交直接返回已有的那张单（开发规格 5.2“约定”）。
 */
@Service
public class OrderService {

    /** 提交的一箱。tareKg、tareName、groupNo、groupK 缺省时按“没用皮重、一箱一称”处理（Web 自建单只给净重）。 */
    public record BoxInput(int seq, BigDecimal netKg, BigDecimal grossKg, BigDecimal tareKg, String tareName,
            Integer groupNo, Integer groupK, boolean manual, BigDecimal rawKg, boolean printed) {
    }

    public record Submission(String batchNo, long vendorId, long productId, long specId, long warehouseId,
            List<BoxInput> boxes) {
    }

    /**
     * 提交结果，APP 完成页用。count、总重、均重是现算的；submittedPrice / submittedAmount 是第一次提交那一刻存下的，
     * 重复提交返回的仍是它们。
     */
    public record Submitted(long id, String code, String batchNo, OrderStatus status, OrderSource source,
            int boxCount, BigDecimal totalNetKg, BigDecimal avgKg, BigDecimal submittedPrice,
            BigDecimal submittedAmount) {
    }

    private final PurchaseOrderMapper orders;
    private final WeighRecordMapper records;
    private final PricingService pricing;
    private final OptionService options;
    private final ArchiveService archive;
    private final SpecService specs;
    private final TransactionTemplate tx;

    public OrderService(PurchaseOrderMapper orders, WeighRecordMapper records, PricingService pricing,
            OptionService options, ArchiveService archive, SpecService specs, TransactionTemplate tx) {
        this.orders = orders;
        this.records = records;
        this.pricing = pricing;
        this.options = options;
        this.archive = archive;
        this.specs = specs;
        this.tx = tx;
    }

    /** 计价预览：不写库。APP 汇总页、规则弹层试算与 Web 自建单录入窗口共用。 */
    public Result quote(long vendorId, long productId, long specId, LocalDate date, List<BigDecimal> boxNetKg,
            Long trialRuleId, BigDecimal trialCoef) {
        return pricing.price(new PricingService.Request(vendorId, productId, specId, date, boxNetKg, null,
                trialRuleId, trialCoef));
    }

    public Submitted submit(Submission s, CurrentUser me) {
        PurchaseOrder order = byBatchNo(s.batchNo());
        if (order == null) {
            try {
                order = tx.execute(status -> insert(s, me));
            } catch (DuplicateKeyException e) {
                // 两个请求同时带着同一个批次号来：后到的撞上唯一键，当作重复提交处理
                order = byBatchNo(s.batchNo());
            }
        }
        return submitted(order);
    }

    // ---------------------------------------------------------------- 插入

    private PurchaseOrder insert(Submission s, CurrentUser me) {
        archive.requireExist(s.vendorId(), s.productId(), s.warehouseId());
        specs.get(s.specId());
        Set<Integer> seqs = new HashSet<>();
        for (BoxInput b : s.boxes()) {
            if (!seqs.add(b.seq())) {
                throw ApiException.invalid("批次序号 " + b.seq() + " 重复了");
            }
        }

        LocalDate bizDate = BatchNo.dateOf(s.batchNo());
        Result price = quote(s.vendorId(), s.productId(), s.specId(), bizDate,
                s.boxes().stream().map(BoxInput::netKg).toList(), null, null);
        if (!price.issues().isEmpty() && "block".equals(options.get("submit.without-price"))) {
            throw new ApiException(ErrorCode.NOT_READY, missing(price));
        }

        OrderSource source = me.role() == Role.SCALE ? OrderSource.APP : OrderSource.WEB;
        LocalDateTime now = LocalDateTime.now();
        PurchaseOrder o = new PurchaseOrder();
        o.code = BatchNo.codeOf(s.batchNo());
        o.batchNo = s.batchNo();
        o.bizDate = bizDate;
        o.vendorId = s.vendorId();
        o.warehouseId = s.warehouseId();
        o.productId = s.productId();
        o.specId = s.specId();
        o.source = source;
        o.operatorId = me.id();
        o.createdAt = now;
        o.status = OrderStatus.PENDING;
        o.submittedPrice = price.unitPrice();
        o.submittedAmount = price.amount();
        o.updatedBy = me.id();
        o.updatedAt = now;
        orders.insert(o);

        for (BoxInput b : s.boxes()) {
            records.insert(record(o.id, b, source));
        }
        return o;
    }

    /** Web 自建单的净重是管理员录的，逐箱不另标“手录”，也没有秤的原始读数（v7 WEB-06）。 */
    static WeighRecord record(long orderId, BoxInput b, OrderSource source) {
        boolean web = source == OrderSource.WEB;
        WeighRecord r = new WeighRecord();
        r.orderId = orderId;
        r.seq = b.seq();
        r.netKg = b.netKg();
        r.grossKg = web ? null : b.grossKg();
        r.tareKg = b.tareKg() == null ? BigDecimal.ZERO : b.tareKg();
        r.tareName = b.tareName() == null ? "" : b.tareName();
        r.groupNo = b.groupNo() == null ? b.seq() : b.groupNo();
        r.groupK = b.groupK() == null ? 1 : b.groupK();
        r.manual = !web && b.manual();
        r.rawKg = web ? null : b.rawKg();
        r.printed = !web && b.printed();
        return r;
    }

    private static String missing(Result price) {
        boolean noRule = price.issues().contains(PricingService.NO_RULE);
        boolean noBase = price.issues().contains(PricingService.NO_BASE_PRICE);
        String what = noRule && noBase ? "没有收购规则，当日也没录基准价"
                : noRule ? "这位农户在该商品规格上没有收购规则" : "当日该规格没录基准价";
        return what + "，现在不能提交";
    }

    // ---------------------------------------------------------------- 读取

    private PurchaseOrder byBatchNo(String batchNo) {
        return orders.selectOne(new QueryWrapper<PurchaseOrder>().eq("batch_no", batchNo));
    }

    private Submitted submitted(PurchaseOrder o) {
        List<BigDecimal> nets = records.selectList(new QueryWrapper<WeighRecord>().eq("order_id", o.id).orderByAsc("seq"))
                .stream().map(r -> r.netKg).toList();
        Result r = quote(o.vendorId, o.productId, o.specId, o.bizDate, nets, null, null);
        return new Submitted(o.id, o.code, o.batchNo, o.status, o.source, r.boxCount(), r.totalKg(), r.avgKg(),
                o.submittedPrice, o.submittedAmount);
    }
}
