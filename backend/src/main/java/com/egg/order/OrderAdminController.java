package com.egg.order;

import java.time.LocalDate;
import java.util.List;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.CurrentUser;
import com.egg.common.PageResult;
import com.egg.order.OrderController.BoxForm;
import com.egg.order.OrderEditService.Replacement;
import com.egg.order.OrderQueryService.BoxLookup;
import com.egg.order.OrderQueryService.Counts;
import com.egg.order.OrderQueryService.NextPending;
import com.egg.order.OrderQueryService.OrderDetail;
import com.egg.order.OrderQueryService.OrderRow;
import com.egg.order.OrderQueryService.Records;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Web 管理端的单据查询与整单修改，以及箱码查询（开发规格 5.2“单据（Web）”）。不标 {@code @Access} 的就是仅管理员。 */
@RestController
public class OrderAdminController {

    /** 整单覆盖：带完整表头和全部流水。收购日期可以改；批次号与单据编号不变。 */
    public record UpdateForm(
            @NotNull(message = "收购日期不能为空") LocalDate bizDate,
            @NotNull(message = "农户不能为空") Long vendorId,
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "规格不能为空") Long specId,
            @NotNull(message = "仓库不能为空") Long warehouseId,
            @NotEmpty(message = "至少要有一箱") List<@Valid BoxForm> boxes) {
    }

    private final OrderQueryService query;
    private final OrderEditService edit;

    public OrderAdminController(OrderQueryService query, OrderEditService edit) {
        this.query = query;
        this.edit = edit;
    }

    /** tab：pending（默认）/ failed / unsent / sent / all。 */
    @GetMapping("/api/orders")
    public PageResult<OrderRow> list(@RequestParam(required = false) String tab, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return query.list(tab, q, page, pageSize);
    }

    @GetMapping("/api/orders/counts")
    public Counts counts() {
        return query.counts();
    }

    /** 没有下一张时返回的各字段都是 null。 */
    @GetMapping("/api/orders/next-pending")
    public NextPending nextPending(@RequestParam(required = false) Long after) {
        return query.nextPending(after);
    }

    @GetMapping("/api/orders/{id}")
    public OrderDetail detail(@PathVariable long id) {
        return query.detail(id);
    }

    @GetMapping("/api/orders/{id}/records")
    public Records records(@PathVariable long id) {
        return query.records(id);
    }

    /** 仅待审核可调，否则 ORDER_LOCKED。后保存的覆盖先保存的。 */
    @PutMapping("/api/orders/{id}")
    public OrderDetail update(@PathVariable long id, @Valid @RequestBody UpdateForm f, CurrentUser me) {
        return edit.replace(id, new Replacement(f.bizDate(), f.vendorId(), f.productId(), f.specId(), f.warehouseId(),
                f.boxes().stream().map(BoxForm::toInput).toList()), me);
    }

    /** code 是箱贴二维码内容 {@code 批次号#批次序号}。只读，不做销售功能（附录 C 第 29 条）。 */
    @Access(Who.ADMIN_OR_SCALE)
    @GetMapping("/api/boxes/lookup")
    public BoxLookup lookup(@RequestParam String code) {
        return query.lookup(code);
    }
}
