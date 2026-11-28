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
import com.egg.common.ApiException;
import com.egg.common.CurrentUser;
import com.egg.common.ErrorCode;
import com.egg.common.Role;
import com.egg.pricing.PricingRule;
import com.egg.pricing.PricingService;
import com.egg.pricing.RuleService;
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

    /** 提交的一箱。tareJin、tareName、groupNo、groupK 缺省时按“没用皮重、一箱一称”处理（Web 自建单只给净重）。 */
    public record BoxInput(int seq, BigDecimal netJin, BigDecimal grossJin, BigDecimal tareJin, String tareName,
            Integer groupNo, Integer groupK, boolean manual, BigDecimal rawJin, boolean printed) {
    }

    /** 商品与规格取自所选规则（附录 E R-1 行）。 */
    public record Submission(String batchNo, long vendorId, long ruleId, long warehouseId, List<BoxInput> boxes) {
    }

    /**
     * 提交结果，APP 完成页用。count、总重、均重是现算的；submittedPrice / submittedAmount 是第一次提交那一刻存下的，
     * 重复提交返回的仍是它们。
     */
    public record Submitted(long id, String code, String batchNo, OrderStatus status, OrderSource source,
            int boxCount, BigDecimal totalNetJin, BigDecimal avgJin, BigDecimal submittedPrice,
            BigDecimal submittedAmount) {
    }

    private final PurchaseOrderMapper orders;
    private final WeighRecordMapper records;
    private final PricingService pricing;
    private final OptionService options;
    private final ArchiveService archive;
    private final RuleService rules;
    private final TransactionTemplate tx;

    public OrderService(PurchaseOrderMapper orders, WeighRecordMapper records, PricingService pricing,
            OptionService options, ArchiveService archive, RuleService rules, TransactionTemplate tx) {
        this.orders = orders;
        this.records = records;
        this.pricing = pricing;
        this.options = options;
        this.archive = archive;
        this.rules = rules;
        this.tx = tx;
    }

    /**
     * 计价预览：不写库。APP 汇总页、规则弹层试算与 Web 自建单录入窗口共用。规格取规则的规格。
     * 换一条规则试算就是换 ruleId；trialCoef、trialLossJin 只换系数、损耗，不改规则本身。
     */
    public Result quote(long ruleId, LocalDate date, List<BigDecimal> boxNetJin, BigDecimal trialCoef,
            BigDecimal trialLossJin) {
        PricingRule rule = rules.requireBuyRule(ruleId);
        return pricing.price(new PricingService.Request(ruleId, rule.specId, date, boxNetJin, null, trialCoef,
                trialLossJin));
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
        PricingRule rule = rules.requireBuyRule(s.ruleId());
        if (!rule.enabled) {
            throw ApiException.invalid("规则「" + rule.name + "」已停用，请换一条规则");
        }
        archive.requireExist(s.vendorId(), rule.productId, s.warehouseId());
        Set<Integer> seqs = new HashSet<>();
        for (BoxInput b : s.boxes()) {
            if (!seqs.add(b.seq())) {
                throw ApiException.invalid("批次序号 " + b.seq() + " 重复了");
            }
        }

        LocalDate bizDate = BatchNo.dateOf(s.batchNo());
        Result price = pricing.price(new PricingService.Request(rule.id, rule.specId, bizDate,
                s.boxes().stream().map(BoxInput::netJin).toList()));
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
        o.productId = rule.productId;
        o.specId = rule.specId;
        o.ruleId = rule.id;
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
        r.netJin = b.netJin();
        r.grossJin = web ? null : b.grossJin();
        r.tareJin = b.tareJin() == null ? BigDecimal.ZERO : b.tareJin();
        r.tareName = b.tareName() == null ? "" : b.tareName();
        r.groupNo = b.groupNo() == null ? b.seq() : b.groupNo();
        r.groupK = b.groupK() == null ? 1 : b.groupK();
        r.manual = !web && b.manual();
        r.rawJin = web ? null : b.rawJin();
        r.printed = !web && b.printed();
        return r;
    }

    private static String missing(Result price) {
        boolean noRule = price.issues().contains(PricingService.NO_RULE);
        boolean noBase = price.issues().contains(PricingService.NO_BASE_PRICE);
        String what = noRule && noBase ? "没有选定价规则，当日也没录基准价"
                : noRule ? "没有选定价规则" : "当日该规格没录基准价";
        return what + "，现在不能提交";
    }

    // ---------------------------------------------------------------- 读取

    private PurchaseOrder byBatchNo(String batchNo) {
        return orders.selectOne(new QueryWrapper<PurchaseOrder>().eq("batch_no", batchNo));
    }

    private Submitted submitted(PurchaseOrder o) {
        List<BigDecimal> nets = records.selectList(new QueryWrapper<WeighRecord>().eq("order_id", o.id).orderByAsc("seq"))
                .stream().map(r -> r.netJin).toList();
        Result r = pricing.price(new PricingService.Request(o.ruleId, o.specId, o.bizDate, nets));
        return new Submitted(o.id, o.code, o.batchNo, o.status, o.source, r.boxCount(), r.totalJin(), r.avgJin(),
                o.submittedPrice, o.submittedAmount);
    }
}
