package com.egg.pricing;

import java.util.List;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.pricing.RuleService.RuleView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 按农户看规则、设置绑定（v7 APP-09、WEB-07）。农户的检索与最近农户在 archive 包（P1-4），这里只管规则这一面。 */
@RestController
@Access(Who.ADMIN_OR_SCALE)
public class FarmerRuleController {

    /** ruleId 为空就是解绑。 */
    public record BindingForm(
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "规格不能为空") Long specId,
            Long ruleId) {
    }

    private final RuleService service;

    public FarmerRuleController(RuleService service) {
        this.service = service;
    }

    @GetMapping("/api/farmers/{id}/rules")
    public List<RuleView> rules(@PathVariable long id) {
        return service.farmerRules(id);
    }

    @PutMapping("/api/farmers/{id}/rule-binding")
    public List<RuleView> bind(@PathVariable long id, @Valid @RequestBody BindingForm f) {
        return service.bindFarmer(id, f.productId(), f.specId(), f.ruleId());
    }
}
