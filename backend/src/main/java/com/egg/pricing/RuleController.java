package com.egg.pricing;

import java.math.BigDecimal;
import java.util.List;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.CurrentUser;
import com.egg.pricing.RuleService.Draft;
import com.egg.pricing.RuleService.RuleView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定价规则（v7 WEB-05、APP-09，按附录 E R-1 行改版：不绑农户或客户，打包规格、皮重与每箱损耗调整）。
 * 管理员与磅秤员都能调：磅秤员在 APP 里只用到改系数与损耗，但不为此拆权限（开发规格 2.4：两个角色以外的权限不做）。
 */
@RestController
@Access(Who.ADMIN_OR_SCALE)
public class RuleController {

    private static final String KIND = "BUY|SELL";
    private static final String KIND_MSG = "规则属性只能是收购规则（BUY）或发货规则（SELL）";

    public record RuleCreate(
            @NotBlank(message = "规则名称不能为空") @Size(max = 100, message = "规则名称不能超过 100 个字") String name,
            @NotNull(message = "规则属性不能为空") @Pattern(regexp = KIND, message = KIND_MSG) String kind,
            @NotNull(message = "适用商品不能为空") Long productId,
            @NotNull(message = "适用规格不能为空") Long specId,
            @NotNull(message = "包装箱（皮重）不能为空") Long tareBoxId,
            /** 每箱损耗调整（斤），正数加、负数减；不传为 0 */
            @Digits(integer = 3, fraction = 2, message = "每箱损耗最多两位小数") BigDecimal lossJin,
            @NotNull(message = "换算系数不能为空") @DecimalMin(value = "0", message = "换算系数不能为负数")
            @Digits(integer = 4, fraction = 4, message = "换算系数最多四位小数") BigDecimal coef,
            Boolean enabled) {
    }

    /** 只改传了的字段。磅秤员在 APP 里改系数、损耗就只传这两项。 */
    public record RulePatch(
            @Pattern(regexp = ".*\\S.*", message = "规则名称不能为空") @Size(max = 100, message = "规则名称不能超过 100 个字") String name,
            @Pattern(regexp = KIND, message = KIND_MSG) String kind,
            Long productId,
            Long specId,
            Long tareBoxId,
            @Digits(integer = 3, fraction = 2, message = "每箱损耗最多两位小数") BigDecimal lossJin,
            @DecimalMin(value = "0", message = "换算系数不能为负数")
            @Digits(integer = 4, fraction = 4, message = "换算系数最多四位小数") BigDecimal coef,
            Boolean enabled) {
    }

    private final RuleService service;

    public RuleController(RuleService service) {
        this.service = service;
    }

    /** @param enabled 传 true 只列启用的（建单选规则） */
    @GetMapping("/api/rules")
    public List<RuleView> list(@RequestParam(required = false) String kind, @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long specId, @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q) {
        return service.list(kind, productId, specId, enabled, q);
    }

    @PostMapping("/api/rules")
    public RuleView create(@Valid @RequestBody RuleCreate f, CurrentUser me) {
        return service.create(new Draft(f.name(), f.kind(), f.productId(), f.specId(), f.tareBoxId(), f.lossJin(),
                f.coef(), f.enabled()), me.id());
    }

    @PutMapping("/api/rules/{id}")
    public RuleView update(@PathVariable long id, @Valid @RequestBody RulePatch f, CurrentUser me) {
        return service.update(id, new Draft(f.name(), f.kind(), f.productId(), f.specId(), f.tareBoxId(), f.lossJin(),
                f.coef(), f.enabled()), me.id());
    }
}
