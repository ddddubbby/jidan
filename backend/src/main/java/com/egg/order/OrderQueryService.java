package com.egg.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.archive.ArchiveService;
import com.egg.archive.ArchiveService.Farmer;
import com.egg.auth.AccountService;
import com.egg.basics.BasePriceService;
import com.egg.basics.Spec;
import com.egg.basics.SpecService;
import com.egg.common.ApiException;
import com.egg.common.ErrorCode;
import com.egg.common.PageResult;
import com.egg.order.SameWeight.Group;
import com.egg.pricing.PricingService;
import com.egg.pricing.PricingService.Result;
import com.egg.pricing.RoundMode;

import org.springframework.stereotype.Service;

/**
 * 单据的只读视图：列表、导航计数、详情、逐箱流水、下一张待审核、箱码查询（开发规格 5.2“单据（Web）”）。
 * <p>
 * 待审核与已作废的单每次读取都按当前的规则、基准价、取整规则实时算（3.3）；已审核、已回传的单读审核时冻结的值，
 * 还原明细时仍走 {@link PricingService#compute}，不另写一份算法。
 */
@Service
public class OrderQueryService {

    public static final String TAB_PENDING = "pending";
    public static final String TAB_FAILED = "failed";
    public static final String TAB_UNSENT = "unsent";
    public static final String TAB_SENT = "sent";
    public static final String TAB_ALL = "all";

    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern BOX_CODE = Pattern.compile("^(" + BatchNo.PATTERN + ")#([1-9]\\d*)$");

    public record Ref(long id, String name) {
    }

    public record VendorRef(long id, String name, String hsCode, List<String> phones) {
    }

    public record SpecRef(long id, String name, BigDecimal stdNetJin) {
    }

    /**
     * 列表的一行。price、amount 待审核时是实时算的，审核后是冻结的；amountChanged 为真表示与 APP 提交时的金额不同。
     * issues 只在实时算的单上有（缺规则、缺基准价），pushError 非空且状态为 APPROVED 即回传失败。
     */
    public record OrderRow(long id, String code, String batchNo, LocalDate bizDate, LocalDateTime createdAt,
            OrderStatus status, OrderSource source, String vendorName, String vendorPhone, String productName,
            String specName, int boxCount, BigDecimal avgJin, BigDecimal price, BigDecimal amount,
            BigDecimal submittedAmount, boolean amountChanged, String operatorName, String pushError, int manualCount,
            List<String> issues) {
    }

    /**
     * @param pricing 计价结果。frozen 为假时是实时算的；为真时是审核那一刻冻结的值
     * @param submittedDiffers 与 APP 提交时的单价或金额不同（提交时没算出价的不算不同）
     */
    public record OrderDetail(long id, String code, String batchNo, LocalDate bizDate, LocalDateTime createdAt,
            OrderStatus status, OrderSource source, Ref operator, Ref approver, LocalDateTime approvedAt,
            VendorRef vendor, Ref warehouse, Ref product, SpecRef spec, String hsId, String pushError,
            BigDecimal submittedPrice, BigDecimal submittedAmount, boolean submittedDiffers, boolean frozen,
            int manualCount, Result pricing) {
    }

    public record Box(int seq, BigDecimal netJin, BigDecimal grossJin, BigDecimal tareJin, String tareName,
            Integer groupNo, Integer groupK, boolean manual, BigDecimal rawJin, boolean printed) {
    }

    /** 逐箱流水与同重归类。editable 即待审核。 */
    public record Records(long orderId, String batchNo, boolean editable, List<Box> boxes, List<Group> groups,
            int manualCount) {
    }

    public record MissingPrice(long specId, String specName) {
    }

    /** 导航提示：待审核数、回传失败数、已审核未回传数（含回传失败的）、当日没录基准价的启用规格。 */
    public record Counts(long pending, long failed, long unsent, List<MissingPrice> missingBasePrices) {
    }

    /** 没有下一张时 id 等字段都是 null。 */
    public record NextPending(Long id, String code, String vendorName) {
    }

    /** 箱码查询的结果。netJin 是这一箱记录的净重，manual 时 rawJin 是秤最初的读数；avgJin、price 待审核时实时算，其余为冻结值。 */
    public record BoxLookup(String code, String batchNo, int seq, BigDecimal netJin, boolean manual, BigDecimal rawJin,
            Ref vendor, Ref product, SpecRef spec, long orderId, String orderCode, OrderStatus status,
            BigDecimal avgJin, BigDecimal price) {
    }

    private final PurchaseOrderMapper orders;
    private final WeighRecordMapper records;
    private final PricingService pricing;
    private final ArchiveService archive;
    private final SpecService specs;
    private final AccountService accounts;
    private final BasePriceService basePrices;

    public OrderQueryService(PurchaseOrderMapper orders, WeighRecordMapper records, PricingService pricing,
            ArchiveService archive, SpecService specs, AccountService accounts, BasePriceService basePrices) {
        this.orders = orders;
        this.records = records;
        this.pricing = pricing;
        this.archive = archive;
        this.specs = specs;
        this.accounts = accounts;
        this.basePrices = basePrices;
    }

    // ---------------------------------------------------------------- 列表

    /** @param tab pending / failed / unsent / sent / all；q 匹配农户名、农户号码、单据编号、批次号 */
    public PageResult<OrderRow> list(String tab, String q, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        QueryWrapper<PurchaseOrder> w = new QueryWrapper<>();
        switch (tab == null ? TAB_PENDING : tab) {
            case TAB_PENDING -> w.eq("status", OrderStatus.PENDING.name());
            // 已审核且带回传失败原因；没带原因的是“待回传”（push.on-approve = manual），只出现在“全部”里
            case TAB_FAILED -> w.eq("status", OrderStatus.APPROVED.name()).isNotNull("push_error");
            // 全部已审核未回传的单（含回传失败的）：push.on-approve = manual 时页签叫“待回传”（开发规格 8.2）
            case TAB_UNSENT -> w.eq("status", OrderStatus.APPROVED.name());
            case TAB_SENT -> w.eq("status", OrderStatus.PUSHED.name());
            case TAB_ALL -> {
            }
            default -> throw ApiException.invalid("不认识的页签：" + tab);
        }
        String key = q == null ? "" : q.trim();
        if (!key.isEmpty()) {
            String like = "%" + key + "%";
            w.and(a -> a.like("code", key).or().like("batch_no", key)
                    .or().apply("vendor_id IN (SELECT id FROM vendor WHERE name LIKE {0})", like)
                    .or().apply("vendor_id IN (SELECT vendor_id FROM vendor_phone WHERE phone LIKE {0})", like));
        }
        long total = orders.selectCount(w);
        w.orderByDesc("biz_date", "created_at", "id").last("LIMIT " + size + " OFFSET " + (long) (p - 1) * size);
        List<PurchaseOrder> list = orders.selectList(w);
        return new PageResult<>(rows(list), total, p, size);
    }

    private List<OrderRow> rows(List<PurchaseOrder> list) {
        Map<Long, List<WeighRecord>> boxes = boxesOf(list.stream().map(o -> o.id).toList());
        Map<Long, Farmer> vendors = archive.farmersByIds(ids(list, o -> o.vendorId));
        Map<Long, String> products = archive.productNames(ids(list, o -> o.productId));
        Map<Long, String> operators = accounts.names(ids(list, o -> o.operatorId));
        Map<Long, Spec> specById = specs.list().stream().collect(Collectors.toMap(s -> s.id, s -> s));
        List<OrderRow> out = new ArrayList<>();
        for (PurchaseOrder o : list) {
            List<WeighRecord> recs = boxes.getOrDefault(o.id, List.of());
            Result r = resultOf(o, recs);
            Farmer v = vendors.get(o.vendorId);
            out.add(new OrderRow(o.id, o.code, o.batchNo, o.bizDate, o.createdAt, o.status, o.source, v.name(),
                    v.phones().isEmpty() ? null : v.phones().get(0), products.get(o.productId),
                    specById.get(o.specId).name, r.boxCount(), r.avgJin(), r.unitPrice(), r.amount(),
                    o.submittedAmount, differs(o, r), operators.get(o.operatorId), o.pushError, manualCount(recs),
                    r.issues()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 导航计数

    public Counts counts() {
        long pending = orders.selectCount(new QueryWrapper<PurchaseOrder>().eq("status", OrderStatus.PENDING.name()));
        long failed = orders.selectCount(new QueryWrapper<PurchaseOrder>()
                .eq("status", OrderStatus.APPROVED.name()).isNotNull("push_error"));
        long unsent = orders.selectCount(new QueryWrapper<PurchaseOrder>().eq("status", OrderStatus.APPROVED.name()));
        List<MissingPrice> missing = basePrices.day(LocalDate.now()).items().stream().filter(i -> i.price() == null)
                .map(i -> new MissingPrice(i.specId(), i.specName())).toList();
        return new Counts(pending, failed, unsent, missing);
    }

    // ---------------------------------------------------------------- 详情与流水

    public OrderDetail detail(long id) {
        PurchaseOrder o = get(id);
        List<WeighRecord> recs = boxesOf(List.of(id)).getOrDefault(id, List.of());
        Result r = resultOf(o, recs);
        Farmer f = archive.farmersByIds(List.of(o.vendorId)).get(o.vendorId);
        Spec s = specs.get(o.specId);
        List<Long> people = new ArrayList<>(List.of(o.operatorId));
        if (o.approverId != null) {
            people.add(o.approverId);
        }
        Map<Long, String> names = accounts.names(people);
        return new OrderDetail(o.id, o.code, o.batchNo, o.bizDate, o.createdAt, o.status, o.source,
                new Ref(o.operatorId, names.get(o.operatorId)),
                o.approverId == null ? null : new Ref(o.approverId, names.get(o.approverId)), o.approvedAt,
                new VendorRef(f.id(), f.name(), f.hsCode(), f.phones()),
                new Ref(o.warehouseId, archive.warehouseNames(List.of(o.warehouseId)).get(o.warehouseId)),
                new Ref(o.productId, archive.productNames(List.of(o.productId)).get(o.productId)),
                new SpecRef(s.id, s.name, s.stdNetJin), o.hsId, o.pushError, o.submittedPrice, o.submittedAmount,
                differs(o, r), isFrozen(o), manualCount(recs), r);
    }

    public Records records(long id) {
        PurchaseOrder o = get(id);
        List<WeighRecord> recs = boxesOf(List.of(id)).getOrDefault(id, List.of());
        List<Box> boxes = recs.stream().map(r -> new Box(r.seq, r.netJin, r.grossJin, r.tareJin, r.tareName, r.groupNo,
                r.groupK, r.manual, r.rawJin, r.printed)).toList();
        return new Records(o.id, o.batchNo, o.status == OrderStatus.PENDING, boxes,
                SameWeight.of(recs.stream().map(r -> r.netJin).toList()), manualCount(recs));
    }

    // ---------------------------------------------------------------- 下一张待审核

    /**
     * 待审核单按收购日期、提交时间从早到晚排，给出排在 after 之后的第一张；它之后没有了就回到最前面那张（不含 after 自己）。
     * after 通常是刚审核完的那张，已经不在待审核里，所以按它的位置而不是按它是否在列表里来找。
     */
    public NextPending nextPending(Long after) {
        Comparator<PurchaseOrder> order = Comparator.<PurchaseOrder, LocalDate>comparing(o -> o.bizDate)
                .thenComparing(o -> o.createdAt).thenComparing(o -> o.id);
        List<PurchaseOrder> pending = orders.selectList(new QueryWrapper<PurchaseOrder>()
                .eq("status", OrderStatus.PENDING.name()).ne(after != null, "id", after)
                .orderByAsc("biz_date", "created_at", "id"));
        PurchaseOrder from = after == null ? null : orders.selectById(after);
        PurchaseOrder next = pending.stream().filter(o -> from == null || order.compare(o, from) > 0).findFirst()
                .orElse(pending.isEmpty() ? null : pending.get(0));
        if (next == null) {
            return new NextPending(null, null, null);
        }
        return new NextPending(next.id, next.code, archive.farmersByIds(List.of(next.vendorId)).get(next.vendorId).name());
    }

    // ---------------------------------------------------------------- 箱码查询

    /** @param code 箱贴二维码内容 {@code 批次号#批次序号}；格式不对与查不到都是 NOT_FOUND */
    public BoxLookup lookup(String code) {
        Matcher m = BOX_CODE.matcher(code == null ? "" : code.trim());
        if (!m.matches()) {
            throw notFoundBox();
        }
        String batchNo = m.group(1);
        int seq = Integer.parseInt(m.group(2));
        PurchaseOrder o = orders.selectOne(new QueryWrapper<PurchaseOrder>().eq("batch_no", batchNo));
        WeighRecord box = o == null ? null : records.selectOne(new QueryWrapper<WeighRecord>()
                .eq("order_id", o.id).eq("seq", seq));
        if (box == null) {
            throw notFoundBox();
        }
        Result r = resultOf(o, boxesOf(List.of(o.id)).getOrDefault(o.id, List.of()));
        Farmer f = archive.farmersByIds(List.of(o.vendorId)).get(o.vendorId);
        Spec s = specs.get(o.specId);
        return new BoxLookup(batchNo + "#" + seq, batchNo, seq, box.netJin, box.manual, box.rawJin,
                new Ref(f.id(), f.name()),
                new Ref(o.productId, archive.productNames(List.of(o.productId)).get(o.productId)),
                new SpecRef(s.id, s.name, s.stdNetJin), o.id, o.code, o.status, r.avgJin(), r.unitPrice());
    }

    private static ApiException notFoundBox() {
        return new ApiException(ErrorCode.NOT_FOUND, "没有找到这个箱码对应的箱");
    }

    // ---------------------------------------------------------------- 共用

    PurchaseOrder get(long id) {
        PurchaseOrder o = orders.selectById(id);
        if (o == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "单据不存在");
        }
        return o;
    }

    /** 审核后冻结了计价结果；P2-4 之前没有任何单会走到这里。 */
    private static boolean isFrozen(PurchaseOrder o) {
        return (o.status == OrderStatus.APPROVED || o.status == OrderStatus.PUSHED) && o.boxCount != null;
    }

    private Result resultOf(PurchaseOrder o, List<WeighRecord> recs) {
        List<BigDecimal> nets = recs.stream().map(r -> r.netJin).toList();
        if (isFrozen(o)) {
            return PricingService.compute(nets, o.stdNetJin, o.basePrice, o.ruleId, o.ruleName, o.ruleCoef,
                    o.ruleLossJin, RoundMode.parse(o.roundMode));
        }
        return pricing.price(new PricingService.Request(o.ruleId, o.specId, o.bizDate, nets));
    }

    /** APP 提交时算出了价、现在的价或金额与它不同。提交时没算出价（缺规则、缺基准价而放行）的不算“变了”。 */
    private static boolean differs(PurchaseOrder o, Result r) {
        if (o.submittedAmount == null || r.amount() == null) {
            return false;
        }
        return o.submittedAmount.compareTo(r.amount()) != 0
                || (o.submittedPrice != null && r.unitPrice() != null && o.submittedPrice.compareTo(r.unitPrice()) != 0);
    }

    private static int manualCount(List<WeighRecord> recs) {
        return (int) recs.stream().filter(r -> Boolean.TRUE.equals(r.manual)).count();
    }

    private Map<Long, List<WeighRecord>> boxesOf(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return records.selectList(new QueryWrapper<WeighRecord>().in("order_id", orderIds).orderByAsc("order_id", "seq"))
                .stream().collect(Collectors.groupingBy(r -> r.orderId));
    }

    private static Set<Long> ids(List<PurchaseOrder> list, java.util.function.Function<PurchaseOrder, Long> f) {
        return list.stream().map(f).collect(Collectors.toCollection(HashSet::new));
    }
}
