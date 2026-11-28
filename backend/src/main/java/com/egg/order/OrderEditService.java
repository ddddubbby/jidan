package com.egg.order;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.archive.ArchiveService;
import com.egg.common.ApiException;
import com.egg.common.CurrentUser;
import com.egg.common.ErrorCode;
import com.egg.order.OrderQueryService.OrderDetail;
import com.egg.order.OrderService.BoxInput;
import com.egg.pricing.PricingRule;
import com.egg.pricing.RuleService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 待审核单的整单修改（开发规格 3.4“互斥”、5.2“修改是整单覆盖”）：表头与流水在同一个事务里整体替换，
 * 事务开头先锁住单据行再看状态，所以修改与审核、作废同时到达时只有一个生效。
 */
@Service
public class OrderEditService {

    /** 整单覆盖的内容。批次号、单据编号、来源、提交时的金额都不变，收购日期可以改。 */
    public record Replacement(LocalDate bizDate, long vendorId, long ruleId, long warehouseId, List<BoxInput> boxes) {
    }

    private final PurchaseOrderMapper orders;
    private final WeighRecordMapper records;
    private final ArchiveService archive;
    private final RuleService rules;
    private final OrderQueryService query;
    private final TransactionTemplate tx;

    public OrderEditService(PurchaseOrderMapper orders, WeighRecordMapper records, ArchiveService archive,
            RuleService rules, OrderQueryService query, TransactionTemplate tx) {
        this.orders = orders;
        this.records = records;
        this.archive = archive;
        this.rules = rules;
        this.query = query;
        this.tx = tx;
    }

    /**
     * Web 改过净重的箱由前端标上手录并记下原读数，这里不逐箱比对（附录 C 第 16 条）；
     * 来源为 WEB 的单与提交时一样不采用毛重、手录标记、原始读数与已打印。
     */
    public OrderDetail replace(long id, Replacement r, CurrentUser me) {
        tx.executeWithoutResult(status -> {
            PurchaseOrder o = orders.selectOne(new QueryWrapper<PurchaseOrder>().eq("id", id).last("FOR UPDATE"));
            if (o == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "单据不存在");
            }
            if (o.status != OrderStatus.PENDING) {
                throw new ApiException(ErrorCode.ORDER_LOCKED, lockedMessage(o.status));
            }
            PricingRule rule = rules.requireBuyRule(r.ruleId());
            if (!rule.enabled && !rule.id.equals(o.ruleId)) {
                throw ApiException.invalid("规则「" + rule.name + "」已停用，请换一条规则");
            }
            archive.requireExist(r.vendorId(), rule.productId, r.warehouseId());
            Set<Integer> seqs = new HashSet<>();
            for (BoxInput b : r.boxes()) {
                if (!seqs.add(b.seq())) {
                    throw ApiException.invalid("批次序号 " + b.seq() + " 重复了");
                }
            }

            o.bizDate = r.bizDate();
            o.vendorId = r.vendorId();
            o.ruleId = rule.id;
            o.productId = rule.productId;
            o.specId = rule.specId;
            o.warehouseId = r.warehouseId();
            o.updatedBy = me.id();
            o.updatedAt = LocalDateTime.now();
            orders.updateById(o);

            records.delete(new QueryWrapper<WeighRecord>().eq("order_id", id));
            for (BoxInput b : r.boxes()) {
                records.insert(OrderService.record(id, b, o.source));
            }
        });
        return query.detail(id);
    }

    private static String lockedMessage(OrderStatus s) {
        return switch (s) {
            case VOID -> "这张单已作废，不能修改";
            default -> "这张单已审核，不能直接修改；要改请先退回待审核";
        };
    }
}
