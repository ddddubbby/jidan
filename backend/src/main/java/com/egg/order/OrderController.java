package com.egg.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.CurrentUser;
import com.egg.common.Role;
import com.egg.order.OrderService.BoxInput;
import com.egg.order.OrderService.Submission;
import com.egg.order.OrderService.Submitted;
import com.egg.pricing.PricingService.Result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** APP 与 Web 自建单共用的建单接口：取批次号、计价预览、提交（开发规格 5.2）。 */
@RestController
public class OrderController {

    /** date 只有 Web 新建单用（收购日期）；APP 取的永远是当日。 */
    public record BatchForm(LocalDate date) {
    }

    public record Batch(String batchNo, LocalDate date) {
    }

    public record QuoteForm(
            @NotNull(message = "农户不能为空") Long vendorId,
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "规格不能为空") Long specId,
            /** 缺省取当日 */
            LocalDate date,
            /** 各箱净重，可以为空：没有箱子时只返回规则与基准价的情况 */
            List<@NotNull(message = "净重不能为空") @DecimalMin(value = "0", inclusive = false, message = "净重必须大于 0") BigDecimal> boxNetKg,
            /** 试算：换一条规则 */
            Long trialRuleId,
            /** 试算：换一个换算系数，与 trialRuleId 同时给时以它为准 */
            BigDecimal trialCoef) {
    }

    public record BoxForm(
            @NotNull(message = "批次序号不能为空") @Positive(message = "批次序号必须大于 0") Integer seq,
            @NotNull(message = "净重不能为空") @DecimalMin(value = "0", inclusive = false, message = "净重必须大于 0")
            @Digits(integer = 6, fraction = 2, message = "净重最多两位小数") BigDecimal netKg,
            BigDecimal grossKg, BigDecimal tareKg, String tareName, Integer groupNo, Integer groupK,
            boolean manual, BigDecimal rawKg, boolean printed) {
    }

    public record SubmitForm(
            @NotBlank(message = "批次号不能为空") @Pattern(regexp = BatchNo.PATTERN, message = "批次号格式不对") String batchNo,
            @NotNull(message = "农户不能为空") Long vendorId,
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "规格不能为空") Long specId,
            @NotNull(message = "仓库不能为空") Long warehouseId,
            @NotEmpty(message = "至少要有一箱") List<@Valid BoxForm> boxes) {
    }

    private final BatchService batches;
    private final OrderService service;

    public OrderController(BatchService batches, OrderService service) {
        this.batches = batches;
        this.service = service;
    }

    @Access(Who.ADMIN_OR_SCALE)
    @PostMapping("/api/batches")
    public Batch batch(@RequestBody(required = false) BatchForm form, CurrentUser me) {
        LocalDate date = me.role() == Role.SCALE || form == null || form.date() == null ? LocalDate.now() : form.date();
        return new Batch(batches.next(date), date);
    }

    @Access(Who.ADMIN_OR_SCALE)
    @PostMapping("/api/orders/quote")
    public Result quote(@Valid @RequestBody QuoteForm f) {
        return service.quote(f.vendorId(), f.productId(), f.specId(), f.date() == null ? LocalDate.now() : f.date(),
                f.boxNetKg() == null ? List.of() : f.boxNetKg(), f.trialRuleId(), f.trialCoef());
    }

    /** 磅秤员提交记为 APP 来源，管理员提交记为 WEB 来源。同一批次号再次提交返回原来那张单。 */
    @Access(Who.ADMIN_OR_SCALE)
    @PostMapping("/api/orders")
    public Submitted submit(@Valid @RequestBody SubmitForm f, CurrentUser me) {
        List<BoxInput> boxes = f.boxes().stream().map(b -> new BoxInput(b.seq(), b.netKg(), b.grossKg(), b.tareKg(),
                b.tareName(), b.groupNo(), b.groupK(), b.manual(), b.rawKg(), b.printed())).toList();
        return service.submit(new Submission(f.batchNo(), f.vendorId(), f.productId(), f.specId(), f.warehouseId(),
                boxes), me);
    }
}
