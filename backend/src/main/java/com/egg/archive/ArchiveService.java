package com.egg.archive;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.egg.chanjet.ChanjetClient;
import com.egg.chanjet.ChanjetException;
import com.egg.common.ApiException;
import com.egg.common.PageResult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 好生意四类档案：同步入库、分页检索，以及建单用的农户查询（开发规格 3.5、5.2）。 */
@Service
public class ArchiveService {

    /** 检索结果的一行。四类档案共用：unitName 只有商品有，phones 只有供应商有。 */
    public record ArchiveRow(long id, String hsCode, String name, String unitName, List<String> phones,
            LocalDateTime syncedAt) {
    }

    /** 建单选农户用。phones 是全部号码，matchedPhones 是尾号命中的那几个（最近农户里为空）。 */
    public record Farmer(long id, String name, String hsCode, List<String> phones, List<String> matchedPhones) {
    }

    public record Status(LocalDateTime lastSyncedAt, long products, long vendors, long customers, long warehouses) {
    }

    private static final int MAX_PAGE_SIZE = 100;
    private static final int RECENT_FARMERS = 5;

    private final ChanjetClient chanjet;
    private final TransactionTemplate tx;
    private final ProductMapper products;
    private final VendorMapper vendors;
    private final VendorPhoneMapper phones;
    private final CustomerMapper customers;
    private final WarehouseMapper warehouses;

    public ArchiveService(ChanjetClient chanjet, TransactionTemplate tx, ProductMapper products, VendorMapper vendors,
            VendorPhoneMapper phones, CustomerMapper customers, WarehouseMapper warehouses) {
        this.chanjet = chanjet;
        this.tx = tx;
        this.products = products;
        this.vendors = vendors;
        this.phones = phones;
        this.customers = customers;
        this.warehouses = warehouses;
    }

    // ---------------------------------------------------------------- 同步

    /**
     * 四类档案全量拉取，按好生意编码覆盖更新；只增改不删（历史单据引用着旧档案）。
     * 先把四类都拉到手再一起写库：任何一类拉取失败，库里保持上次的数据。
     */
    public Status sync() {
        List<ChanjetClient.Product> ps;
        List<ChanjetClient.Vendor> vs;
        List<ChanjetClient.Customer> cs;
        List<ChanjetClient.Warehouse> ws;
        try {
            ps = chanjet.listProducts();
            vs = chanjet.listVendors();
            cs = chanjet.listCustomers();
            ws = chanjet.listWarehouses();
        } catch (ChanjetException e) {
            throw ApiException.invalid("同步失败，保留了上次的数据：" + e.getMessage());
        }
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(s -> {
            ps.forEach(p -> products.upsert(p.code(), p.name(), p.unitName() == null ? "箱" : p.unitName(), now));
            cs.forEach(c -> customers.upsert(c.code(), c.name(), now));
            ws.forEach(w -> warehouses.upsert(w.code(), w.name(), now));
            saveVendors(vs, now);
        });
        return status();
    }

    private void saveVendors(List<ChanjetClient.Vendor> list, LocalDateTime now) {
        if (list.isEmpty()) {
            return;
        }
        list.forEach(v -> vendors.upsert(v.code(), v.name(), now));
        Map<String, Long> idByCode = vendors.selectList(new QueryWrapper<Vendor>().in("hs_code",
                list.stream().map(ChanjetClient.Vendor::code).toList()))
                .stream().collect(Collectors.toMap(v -> v.hsCode, v -> v.id));
        // 号码以好生意为准：先清掉这批农户原有的，再按这次拉到的重写，好生意里删掉的号码这里也跟着没有
        phones.delete(new QueryWrapper<VendorPhone>().in("vendor_id", idByCode.values()));
        for (ChanjetClient.Vendor v : list) {
            for (String phone : v.phones().stream().map(String::trim).filter(p -> !p.isEmpty()).distinct().toList()) {
                VendorPhone row = new VendorPhone();
                row.vendorId = idByCode.get(v.code());
                row.phone = phone;
                row.tail4 = tail4(phone);
                phones.insert(row);
            }
        }
    }

    /** 号码里可能带区号连字符（0354-6221234），尾号只认数字。 */
    private static String tail4(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return digits.substring(Math.max(0, digits.length() - 4));
    }

    public Status status() {
        LocalDateTime last = Stream.of(maxSyncedAt(products), maxSyncedAt(vendors),
                maxSyncedAt(customers), maxSyncedAt(warehouses)).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new Status(last, products.selectCount(null), vendors.selectCount(null), customers.selectCount(null),
                warehouses.selectCount(null));
    }

    private <E> LocalDateTime maxSyncedAt(BaseMapper<E> mapper) {
        // 表是空的时候 MAX 为 null，MyBatis 返回的那一行是 null
        Map<String, Object> row = mapper.selectMaps(new QueryWrapper<E>().select("MAX(synced_at) AS t")).get(0);
        Object t = row == null ? null : row.get("t");
        return t instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) t;
    }

    // ---------------------------------------------------------------- 检索

    /** @param type products / vendors / customers / warehouses；q 匹配名称与编码，供应商还匹配号码 */
    public PageResult<ArchiveRow> search(String type, String q, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        String key = q == null ? "" : q.trim();
        return switch (type) {
            case "products" -> paged(products, byNameOrCode(new QueryWrapper<>(), key), p, size,
                    list -> list.stream().map(x -> new ArchiveRow(x.id, x.hsCode, x.name, x.unitName, null, x.syncedAt)).toList());
            case "customers" -> paged(customers, byNameOrCode(new QueryWrapper<>(), key), p, size,
                    list -> list.stream().map(x -> new ArchiveRow(x.id, x.hsCode, x.name, null, null, x.syncedAt)).toList());
            case "warehouses" -> paged(warehouses, byNameOrCode(new QueryWrapper<>(), key), p, size,
                    list -> list.stream().map(x -> new ArchiveRow(x.id, x.hsCode, x.name, null, null, x.syncedAt)).toList());
            case "vendors" -> {
                QueryWrapper<Vendor> w = new QueryWrapper<>();
                if (!key.isEmpty()) {
                    w.and(a -> a.like("name", key).or().like("hs_code", key)
                            .or().apply("id IN (SELECT vendor_id FROM vendor_phone WHERE phone LIKE {0})", "%" + key + "%"));
                }
                yield paged(vendors, w, p, size, list -> {
                    Map<Long, List<String>> byVendor = phonesOf(list.stream().map(v -> v.id).toList());
                    return list.stream().map(v -> new ArchiveRow(v.id, v.hsCode, v.name, null,
                            byVendor.getOrDefault(v.id, List.of()), v.syncedAt)).toList();
                });
            }
            default -> throw ApiException.invalid("不认识的档案类型：" + type);
        };
    }

    private static <E> QueryWrapper<E> byNameOrCode(QueryWrapper<E> w, String key) {
        if (!key.isEmpty()) {
            w.and(a -> a.like("name", key).or().like("hs_code", key));
        }
        return w;
    }

    private <E> PageResult<ArchiveRow> paged(BaseMapper<E> mapper, QueryWrapper<E> w, int page, int size,
            Function<List<E>, List<ArchiveRow>> toRows) {
        long total = mapper.selectCount(w);
        w.orderByAsc("id").last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size);
        return new PageResult<>(toRows.apply(mapper.selectList(w)), total, page, size);
    }

    // ---------------------------------------------------------------- 建单用

    /** 商品全量，APP 的 bootstrap 下发用。 */
    public List<Product> allProducts() {
        return products.selectList(new QueryWrapper<Product>().orderByAsc("id"));
    }

    /** 仓库全量，APP 的 bootstrap 下发用。 */
    public List<Warehouse> allWarehouses() {
        return warehouses.selectList(new QueryWrapper<Warehouse>().orderByAsc("id"));
    }

    /** 提交单据时确认农户、商品、仓库都在档案里。好生意里删掉的档案本系统保留，所以只会是传错了 id。 */
    public void requireExist(long vendorId, long productId, long warehouseId) {
        if (vendors.selectById(vendorId) == null) {
            throw ApiException.invalid("农户不存在");
        }
        if (products.selectById(productId) == null) {
            throw ApiException.invalid("商品不存在");
        }
        if (warehouses.selectById(warehouseId) == null) {
            throw ApiException.invalid("仓库不存在");
        }
    }

    // ---------------------------------------------------------------- 建单选农户

    /** 按手机号后四位查农户；同名的靠 matchedPhones 区分。没有匹配返回空列表，由 APP 提示。 */
    public List<Farmer> farmersByTail(String tail) {
        if (tail == null || !tail.matches("\\d{4}")) {
            throw ApiException.invalid("请输入手机号后四位");
        }
        List<VendorPhone> hits = phones.selectList(new QueryWrapper<VendorPhone>().eq("tail4", tail).orderByAsc("id"));
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> matched = new LinkedHashMap<>();
        for (VendorPhone h : hits) {
            matched.computeIfAbsent(h.vendorId, k -> new ArrayList<>()).add(h.phone);
        }
        List<Vendor> found = vendors.selectList(new QueryWrapper<Vendor>().in("id", matched.keySet()).orderByAsc("id"));
        return farmers(found, matched);
    }

    /** 当前磅秤员最近收过的 5 位农户，最近的在前。 */
    public List<Farmer> recentFarmers(long operatorId) {
        return farmers(vendors.recentOf(operatorId, RECENT_FARMERS), Map.of());
    }

    private List<Farmer> farmers(List<Vendor> list, Map<Long, List<String>> matched) {
        Map<Long, List<String>> all = phonesOf(list.stream().map(v -> v.id).toList());
        return list.stream().map(v -> new Farmer(v.id, v.name, v.hsCode, all.getOrDefault(v.id, List.of()),
                matched.getOrDefault(v.id, List.of()))).toList();
    }

    private Map<Long, List<String>> phonesOf(List<Long> vendorIds) {
        if (vendorIds.isEmpty()) {
            return Map.of();
        }
        return phones.selectList(new QueryWrapper<VendorPhone>().in("vendor_id", vendorIds).orderByAsc("id")).stream()
                .collect(Collectors.groupingBy(p -> p.vendorId, Collectors.mapping(p -> p.phone, Collectors.toList())));
    }
}
