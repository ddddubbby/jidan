package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.stereotype.Service;

@Service
public class BasePriceService {

    /** @param price 当天的基准价，没录为 null；prevPrice 是前一天的，供页面“沿用前一天” */
    public record Item(long specId, String specName, BigDecimal stdNetJin, BigDecimal price, BigDecimal prevPrice) {
    }

    public record Day(LocalDate date, List<Item> items) {
    }

    private final BasePriceMapper mapper;
    private final SpecService specs;

    public BasePriceService(BasePriceMapper mapper, SpecService specs) {
        this.mapper = mapper;
        this.specs = specs;
    }

    /** 某日各启用规格的基准价（没录的为 null），加上前一天的价。 */
    public Day day(LocalDate date) {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (BasePrice p : mapper.selectList(new QueryWrapper<BasePrice>().in("price_date", date, date.minusDays(1)))) {
            prices.put(p.priceDate + "#" + p.specId, p.basePrice);
        }
        List<Item> items = specs.listEnabled().stream().map(s -> new Item(s.id, s.name, s.stdNetJin,
                prices.get(date + "#" + s.id), prices.get(date.minusDays(1) + "#" + s.id))).toList();
        return new Day(date, items);
    }

    /** 录入或修改一格。同一天同一规格重复录入是覆盖。 */
    public Item save(LocalDate date, long specId, BigDecimal price, Long operatorId) {
        specs.get(specId);
        mapper.upsert(date, specId, price, operatorId, LocalDateTime.now());
        return day(date).items().stream().filter(i -> i.specId() == specId).findFirst()
                // 停用的规格也能录价，只是不在页面列表里
                .orElseGet(() -> {
                    Spec s = specs.get(specId);
                    return new Item(specId, s.name, s.stdNetJin, price, null);
                });
    }
}
