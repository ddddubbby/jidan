package com.egg.basics;

import com.egg.common.CurrentUser;
import java.math.BigDecimal;
import java.util.List;

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
import org.springframework.web.bind.annotation.RestController;

/** 包装箱皮重与热键（v7 WEB-03）。 */
@RestController
public class TareBoxController {

    public record TareCreate(
            @NotBlank(message = "包装箱名称不能为空") @Size(max = 50, message = "包装箱名称不能超过 50 个字") String name,
            @NotNull(message = "皮重不能为空") @DecimalMin(value = "0", inclusive = false, message = "皮重必须大于 0")
            @Digits(integer = 6, fraction = 2, message = "皮重最多两位小数") BigDecimal tareKg,
            /** 不传按“是”，新增的包装箱默认出现在 APP 皮重选项里 */
            Boolean hotkey) {
    }

    /** 只改传了的字段：就地改数值、点启用或热键开关，都走这一个接口。 */
    public record TarePatch(
            @Pattern(regexp = ".*\\S.*", message = "包装箱名称不能为空") @Size(max = 50, message = "包装箱名称不能超过 50 个字") String name,
            @DecimalMin(value = "0", inclusive = false, message = "皮重必须大于 0")
            @Digits(integer = 6, fraction = 2, message = "皮重最多两位小数") BigDecimal tareKg,
            Boolean hotkey,
            Boolean enabled) {
    }

    private final TareBoxService service;

    public TareBoxController(TareBoxService service) {
        this.service = service;
    }

    @GetMapping("/api/tares")
    public List<TareBox> list() {
        return service.list();
    }

    @PostMapping("/api/tares")
    public TareBox create(@Valid @RequestBody TareCreate form, CurrentUser me) {
        return service.create(form.name(), form.tareKg(), form.hotkey(), me.id());
    }

    @PutMapping("/api/tares/{id}")
    public TareBox update(@PathVariable long id, @Valid @RequestBody TarePatch form, CurrentUser me) {
        return service.update(id, form.name(), form.tareKg(), form.hotkey(), form.enabled(), me.id());
    }
}
