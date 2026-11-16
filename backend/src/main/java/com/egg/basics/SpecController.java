package com.egg.basics;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 包装规格与标准净含量（v7 WEB-02）。 */
@RestController
public class SpecController {

    public record SpecCreate(
            @NotBlank(message = "规格名称不能为空") @Size(max = 50, message = "规格名称不能超过 50 个字") String name,
            @NotNull(message = "每箱枚数不能为空") @Positive(message = "每箱枚数必须大于 0") Integer eggCount,
            @NotNull(message = "标准净含量不能为空") @DecimalMin(value = "0", inclusive = false, message = "标准净含量必须大于 0")
            @Digits(integer = 6, fraction = 2, message = "标准净含量最多两位小数") BigDecimal stdNetKg) {
    }

    /** 只改传了的字段：就地改一个数、点一下启用开关，都走这一个接口。 */
    public record SpecPatch(
            @Pattern(regexp = ".*\\S.*", message = "规格名称不能为空") @Size(max = 50, message = "规格名称不能超过 50 个字") String name,
            @Positive(message = "每箱枚数必须大于 0") Integer eggCount,
            @DecimalMin(value = "0", inclusive = false, message = "标准净含量必须大于 0")
            @Digits(integer = 6, fraction = 2, message = "标准净含量最多两位小数") BigDecimal stdNetKg,
            Boolean enabled) {
    }

    private final SpecService service;

    public SpecController(SpecService service) {
        this.service = service;
    }

    @GetMapping("/api/specs")
    public List<Spec> list() {
        return service.list();
    }

    @PostMapping("/api/specs")
    public Spec create(@Valid @RequestBody SpecCreate form) {
        // 操作人：登录拦截（P1-2）合入后改传当前登录人，下同
        return service.create(form.name(), form.eggCount(), form.stdNetKg(), null);
    }

    @PutMapping("/api/specs/{id}")
    public Spec update(@PathVariable long id, @Valid @RequestBody SpecPatch form) {
        return service.update(id, form.name(), form.eggCount(), form.stdNetKg(), form.enabled(), null);
    }
}
