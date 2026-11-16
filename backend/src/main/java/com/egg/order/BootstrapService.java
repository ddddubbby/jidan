package com.egg.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.egg.archive.ArchiveService;
import com.egg.basics.BasePriceService;
import com.egg.basics.OptionService;
import com.egg.basics.Options.Group;
import com.egg.basics.SpecService;
import com.egg.basics.TareBoxService;

import org.springframework.stereotype.Service;

/** APP 一次拉齐建单要用的东西（开发规格 5.2）。下发的值下一次建单生效；APP 缺哪一项就用自己的默认值。 */
@Service
public class BootstrapService {

    public record Named(long id, String name) {
    }

    /** basePrice 是当日基准价，没录为 null：APP 在规格上标“无基准价”（v7 APP-02）。 */
    public record SpecItem(long id, String name, int eggCount, BigDecimal stdNetKg, BigDecimal basePrice) {
    }

    public record Tare(long id, String name, BigDecimal tareKg) {
    }

    /**
     * @param settings 业务设置（打印时机、标签内容、取整方式、两个设备型号……），键是选项键
     * @param options  APP 端的选项（秤、称重、打印、提交），键是选项键
     */
    public record Bootstrap(LocalDate date, List<Named> products, List<SpecItem> specs, List<Named> warehouses,
            List<Tare> tares, Map<String, String> settings, Map<String, String> options) {
    }

    /** 高级选项里只有后端自己用的那几项（回传好生意）不下发。 */
    private static final String BACKEND_ONLY = "push.";

    private final ArchiveService archive;
    private final SpecService specs;
    private final BasePriceService basePrices;
    private final TareBoxService tares;
    private final OptionService options;

    public BootstrapService(ArchiveService archive, SpecService specs, BasePriceService basePrices,
            TareBoxService tares, OptionService options) {
        this.archive = archive;
        this.specs = specs;
        this.basePrices = basePrices;
        this.tares = tares;
        this.options = options;
    }

    public Bootstrap load() {
        LocalDate today = LocalDate.now();
        Map<Long, BigDecimal> priceOf = new HashMap<>();
        basePrices.day(today).items().forEach(i -> priceOf.put(i.specId(), i.price()));

        Map<String, String> appOptions = new LinkedHashMap<>();
        options.values(Group.ADAPTER, Group.POLICY).forEach((k, v) -> {
            if (!k.startsWith(BACKEND_ONLY)) {
                appOptions.put(k, v);
            }
        });
        return new Bootstrap(today,
                archive.allProducts().stream().map(p -> new Named(p.id, p.name)).toList(),
                specs.listEnabled().stream()
                        .map(s -> new SpecItem(s.id, s.name, s.eggCount, s.stdNetKg, priceOf.get(s.id))).toList(),
                archive.allWarehouses().stream().map(w -> new Named(w.id, w.name)).toList(),
                tares.listHotkeys().stream().map(t -> new Tare(t.id, t.name, t.tareKg)).toList(),
                options.values(Group.SETTING), appOptions);
    }
}
