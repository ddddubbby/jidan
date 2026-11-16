package com.egg.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.egg.archive.ArchiveService;
import com.egg.basics.OptionService;
import com.egg.chanjet.ChanjetClient;
import com.egg.chanjet.ChanjetException;
import com.egg.chanjet.StockInPayload;
import com.egg.common.ApiException;
import com.egg.common.CurrentUser;
import com.egg.common.ErrorCode;
import com.egg.pricing.PricingService;
import com.egg.pricing.PricingService.Result;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 单据状态流转里的审核、回传、退回待审核、标记为已回传、作废（开发规格 3.4）。
 * <p>
 * 每个动作各在一个事务里：先锁这张单的行（{@code SELECT … FOR UPDATE}），再检查状态，再动手。
 * 两个同时到达的审核只有一个生效，另一个等到锁后发现已不是待审核，直接返回当前状态。
 * 事务会跨过对好生意的那次调用（超时由 {@code ChanjetClient} 的实现负责，真实现在 P8-4）；
 * 调用途中进程中断的情形不处理（开发规格 3.4）。
 * <p>
 * 未经实物验证：只在模拟好生意（{@code FakeChanjetClient}）上验证过，没有接触过真实的好生意账套。
 */
@Service
public class OrderFlowService {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowService.class);

    /** 没收到答复时写进 push_error 的提示（开发规格 3.4），原文照写。 */
    static final String NO_REPLY_HINT = "没有收到好生意的答复，单据可能已经生成。请先到好生意按单据编号查一下："
            + "已经有了，点“标记为已回传”；没有，重新回传或退回";

    /** 动作做完后的单据状态。详情页另由 P2-3 的 GET /orders/{id} 提供，这里只返回状态与回传结果。 */
    public record Flow(long id, String code, String batchNo, OrderStatus status, LocalDateTime approvedAt, String hsId,
            String pushError, Integer boxCount, BigDecimal totalNetKg, BigDecimal avgKg, BigDecimal price,
            BigDecimal amount) {
    }

    private final PurchaseOrderMapper orders;
    private final WeighRecordMapper records;
    private final PricingService pricing;
    private final OptionService options;
    private final ArchiveService archive;
    private final ChanjetClient chanjet;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public OrderFlowService(PurchaseOrderMapper orders, WeighRecordMapper records, PricingService pricing,
            OptionService options, ArchiveService archive, ChanjetClient chanjet, ObjectMapper json,
            TransactionTemplate tx) {
        this.orders = orders;
        this.records = records;
        this.pricing = pricing;
        this.options = options;
        this.archive = archive;
        this.chanjet = chanjet;
        this.json = json;
        this.tx = tx;
    }

    // ---------------------------------------------------------------- 审核

    /** 冻结计价结果、组装并存下报文，转已审核；选项 push.on-approve = auto 时紧接着回传一次。 */
    public Flow approve(long id, CurrentUser me) {
        return tx.execute(s -> {
            PurchaseOrder o = lock(id);
            if (o.status == OrderStatus.APPROVED || o.status == OrderStatus.PUSHED) {
                return flow(o);
            }
            if (o.status == OrderStatus.VOID) {
                throw new ApiException(ErrorCode.ORDER_LOCKED, "这张单已作废，不能审核");
            }
            List<BigDecimal> nets = netKgs(o.id);
            if (nets.isEmpty()) {
                throw new ApiException(ErrorCode.NOT_READY, "这张单没有称重流水，现在不能审核");
            }
            Result r = pricing.price(new PricingService.Request(o.vendorId, o.productId, o.specId, o.bizDate, nets));
            if (!r.issues().isEmpty()) {
                throw new ApiException(ErrorCode.NOT_READY, missing(r));
            }

            o.roundMode = r.roundMode().name();
            o.boxCount = r.boxCount();
            o.totalNetKg = r.totalKg();
            o.avgKg = r.avgKg();
            o.stdNetKg = r.standardNetKg();
            o.basePrice = r.basePrice();
            o.ruleId = r.ruleId();
            o.ruleName = r.ruleName();
            o.ruleCoef = r.coefficient();
            o.price = r.unitPrice();
            o.amount = r.amount();
            o.pushPayload = payload(o, nets);
            o.pushError = null;
            o.status = OrderStatus.APPROVED;
            o.approverId = me.id();
            o.approvedAt = LocalDateTime.now();
            touch(o, me);
            if ("auto".equals(options.get("push.on-approve"))) {
                pushNow(o);
            }
            persist(o);
            return flow(o);
        });
    }

    /** 报文只在这里组装一次；开票类型、税率、备注选项、档案编码都以审核这一刻为准。 */
    private String payload(PurchaseOrder o, List<BigDecimal> nets) {
        String comments = switch (options.get("push.remark")) {
            case "off" -> null;
            case "summary" -> Remark.summary(nets).text();
            default -> Remark.build(nets, Integer.parseInt(options.get("push.remark-max-length"))).text();
        };
        var product = archive.product(o.productId);
        String warehouseCode = archive.warehouse(o.warehouseId).hsCode;
        return StockInPayload.build(json, new StockInPayload.Input(o.bizDate, o.code, options.get("invoice.type"),
                archive.vendor(o.vendorId).hsCode, warehouseCode, comments, product.hsCode, o.boxCount,
                product.unitName == null ? "箱" : product.unitName, o.price,
                new BigDecimal(options.get("invoice.tax-rate")), o.amount));
    }

    private static String missing(Result r) {
        boolean noRule = r.issues().contains(PricingService.NO_RULE);
        boolean noBase = r.issues().contains(PricingService.NO_BASE_PRICE);
        String what = noRule && noBase ? "没有收购规则，当日也没录基准价"
                : noRule ? "这位农户在该商品规格上没有收购规则" : "当日该规格没录基准价";
        return what + "，现在不能审核";
    }

    // ---------------------------------------------------------------- 回传

    /** 首次回传与重新回传是同一个动作：把审核时存下的报文原样再发。失败不是接口错误，单据停在已审核并带原因。 */
    public Flow push(long id, CurrentUser me) {
        return tx.execute(s -> {
            PurchaseOrder o = lock(id);
            if (o.status == OrderStatus.PUSHED) {
                return flow(o);
            }
            if (o.status != OrderStatus.APPROVED) {
                throw new ApiException(ErrorCode.ORDER_LOCKED, "这张单" + (o.status == OrderStatus.VOID ? "已作废" : "还没审核") + "，不能回传");
            }
            touch(o, me);
            pushNow(o);
            persist(o);
            return flow(o);
        });
    }

    private void pushNow(PurchaseOrder o) {
        log.info("回传好生意 单据 {} 报文：{}", o.code, o.pushPayload);
        try {
            o.hsId = chanjet.addPurchaseStockIn(o.pushPayload);
            o.pushError = null;
            o.status = OrderStatus.PUSHED;
            log.info("回传好生意 单据 {} 成功，好生意单据 ID：{}", o.code, o.hsId);
        } catch (ChanjetException.NoReply e) {
            log.warn("回传好生意 单据 {} 没有答复：{}", o.code, e.getMessage());
            o.pushError = NO_REPLY_HINT;
        } catch (ChanjetException e) {
            log.warn("回传好生意 单据 {} 失败：{}", o.code, e.getMessage());
            o.pushError = e.getMessage();
        }
    }

    // ---------------------------------------------------------------- 退回、标记、作废

    /** 退回待审核：清空冻结字段、报文、失败原因与审核人，回到“实时算”。要改任何内容，包括让新的系统设置生效，都走这里。 */
    public Flow unapprove(long id, CurrentUser me) {
        return tx.execute(s -> {
            PurchaseOrder o = lock(id);
            if (o.status == OrderStatus.PENDING) {
                return flow(o);
            }
            if (o.status != OrderStatus.APPROVED) {
                throw new ApiException(ErrorCode.ORDER_LOCKED,
                        o.status == OrderStatus.PUSHED ? "已回传的单不能退回待审核" : "这张单已作废，不能退回待审核");
            }
            o.status = OrderStatus.PENDING;
            o.approverId = null;
            o.approvedAt = null;
            o.hsId = null;
            o.pushError = null;
            o.pushPayload = null;
            o.roundMode = null;
            o.boxCount = null;
            o.totalNetKg = null;
            o.avgKg = null;
            o.stdNetKg = null;
            o.basePrice = null;
            o.ruleId = null;
            o.ruleName = null;
            o.ruleCoef = null;
            o.price = null;
            o.amount = null;
            touch(o, me);
            persist(o);
            return flow(o);
        });
    }

    /** 管理员确认好生意里已经有这张单（没答复其实成功了，或是手工录进去的）；可填好生意单号。 */
    public Flow markPushed(long id, String hsId, CurrentUser me) {
        return tx.execute(s -> {
            PurchaseOrder o = lock(id);
            if (o.status == OrderStatus.PUSHED) {
                return flow(o);
            }
            if (o.status != OrderStatus.APPROVED) {
                throw new ApiException(ErrorCode.ORDER_LOCKED, "这张单" + (o.status == OrderStatus.VOID ? "已作废" : "还没审核") + "，不能标记为已回传");
            }
            o.hsId = hsId == null || hsId.isBlank() ? null : hsId.trim();
            o.pushError = null;
            o.status = OrderStatus.PUSHED;
            touch(o, me);
            persist(o);
            return flow(o);
        });
    }

    /** 作废待审核单：留在“全部”页签里，批次号不再复用，不能恢复。 */
    public Flow voidOrder(long id, CurrentUser me) {
        return tx.execute(s -> {
            PurchaseOrder o = lock(id);
            if (o.status == OrderStatus.VOID) {
                return flow(o);
            }
            if (o.status != OrderStatus.PENDING) {
                throw new ApiException(ErrorCode.ORDER_LOCKED, "只有待审核的单可以作废，已审核的单请先退回待审核");
            }
            o.status = OrderStatus.VOID;
            touch(o, me);
            persist(o);
            return flow(o);
        });
    }

    // ---------------------------------------------------------------- 公共

    /** 行锁要在事务里取；取不到这张单就是传错了 id。 */
    private PurchaseOrder lock(long id) {
        PurchaseOrder o = orders.selectOne(new QueryWrapper<PurchaseOrder>().eq("id", id).last("FOR UPDATE"));
        if (o == null) {
            throw ApiException.invalid("单据不存在");
        }
        return o;
    }

    private List<BigDecimal> netKgs(long orderId) {
        return records.selectList(new QueryWrapper<WeighRecord>().eq("order_id", orderId).orderByAsc("seq"))
                .stream().map(r -> r.netKg).toList();
    }

    private static void touch(PurchaseOrder o, CurrentUser me) {
        o.updatedBy = me.id();
        o.updatedAt = LocalDateTime.now();
    }

    /** 状态流转会把一些字段置回 null，updateById 会跳过 null，所以逐列写。 */
    private void persist(PurchaseOrder o) {
        orders.update(null, new UpdateWrapper<PurchaseOrder>().eq("id", o.id)
                .set("status", o.status.name())
                .set("approver_id", o.approverId)
                .set("approved_at", o.approvedAt)
                .set("hs_id", o.hsId)
                .set("push_error", o.pushError)
                .set("push_payload", o.pushPayload)
                .set("round_mode", o.roundMode)
                .set("box_count", o.boxCount)
                .set("total_net_kg", o.totalNetKg)
                .set("avg_kg", o.avgKg)
                .set("std_net_kg", o.stdNetKg)
                .set("base_price", o.basePrice)
                .set("rule_id", o.ruleId)
                .set("rule_name", o.ruleName)
                .set("rule_coef", o.ruleCoef)
                .set("price", o.price)
                .set("amount", o.amount)
                .set("updated_by", o.updatedBy)
                .set("updated_at", o.updatedAt));
    }

    private static Flow flow(PurchaseOrder o) {
        return new Flow(o.id, o.code, o.batchNo, o.status, o.approvedAt, o.hsId, o.pushError, o.boxCount,
                o.totalNetKg, o.avgKg, o.price, o.amount);
    }
}
