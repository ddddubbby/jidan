package com.egg.basics;

import com.egg.common.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;

import com.egg.basics.BasePriceService.Day;
import com.egg.basics.BasePriceService.Item;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 每日市场基准价维护（v7 WEB-04）。 */
@RestController
public class BasePriceController {

    public record BasePriceForm(
            @NotNull(message = "日期不能为空") LocalDate date,
            @NotNull(message = "规格不能为空") Long specId,
            @NotNull(message = "基准价不能为空") @DecimalMin(value = "0", inclusive = false, message = "基准价必须大于 0")
            @Digits(integer = 9, fraction = 2, message = "基准价最多两位小数") BigDecimal price) {
    }

    private final BasePriceService service;

    public BasePriceController(BasePriceService service) {
        this.service = service;
    }

    /** 不带 date 就是服务器当日。 */
    @GetMapping("/api/base-prices")
    public Day day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(date == null ? LocalDate.now() : date);
    }

    @PutMapping("/api/base-prices")
    public Item save(@Valid @RequestBody BasePriceForm form, CurrentUser me) {
        return service.save(form.date(), form.specId(), form.price(), me.id());
    }
}
