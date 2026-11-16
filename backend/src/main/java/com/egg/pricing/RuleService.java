package com.egg.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.basics.Spec;
import com.egg.basics.SpecService;
import com.egg.common.ApiException;
import com.egg.common.ErrorCode;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定价规则的新建、修改、筛选、绑定（v7 WEB-05、APP-09）。
 * <p>
 * “农户 + 商品 + 规格只能命中一条启用的收购规则”在这里保存时检查，不做数据库约束（开发规格 5.1）。
 * 商品、农户、客户的名称直接读 P1-1 的表：档案的同步与检索是 archive 包（P1-4）的事，这里只需要按 id 取名字。
 */
@Service
public class RuleService {

    /** 绑定对象。tail4 只有农户有（取第一个号码的后四位），重名的农户靠它区分。 */
    public record Target(long id, String name, String tail4) {
    }

    /** bindCount 就是“N 户共用”的 N。 */
    public record RuleView(long id, String name, String kind, long productId, String productName, long specId,
            String specName, BigDecimal coef, boolean enabled, List<Target> binds, int bindCount) {
    }

    /** 这个农户在同一商品规格上已经绑了这条规则。 */
    public record Conflict(long vendorId, String vendorName, long ruleId, String ruleName) {
    }

    /** 新建与修改共用；修改时 null 的字段不动，bindIds 为 null 表示不动绑定、空列表表示全部解绑。 */
    public record Draft(String name, String kind, Long productId, Long specId, BigDecimal coef, Boolean enabled,
            List<Long> bindIds) {
    }

    private final PricingRuleMapper rules;
    private final PricingRuleBindMapper binds;
    private final SpecService specs;
    private final NamedParameterJdbcTemplate jdbc;

    public RuleService(PricingRuleMapper rules, PricingRuleBindMapper binds, SpecService specs,
            NamedParameterJdbcTemplate jdbc) {
        this.rules = rules;
        this.binds = binds;
        this.specs = specs;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 查询

    /** q 按空格拆词，每个词都要命中规则名、商品、规格、绑定对象的名称或农户尾号之一。 */
    public List<RuleView> list(String kind, Long productId, Long specId, String q) {
        QueryWrapper<PricingRule> w = new QueryWrapper<PricingRule>().orderByAsc("id");
        if (kind != null && !kind.isBlank()) {
            w.eq("kind", kind);
        }
        if (productId != null) {
            w.eq("product_id", productId);
        }
        if (specId != null) {
            w.eq("spec_id", specId);
        }
        List<RuleView> all = views(rules.selectList(w));
        String[] tokens = q == null ? new String[0] : q.trim().split("\\s+");
        return all.stream().filter(v -> {
            for (String t : tokens) {
                if (!t.isEmpty() && !matches(v, t)) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    private static boolean matches(RuleView v, String t) {
        if (v.name().contains(t) || v.productName().contains(t) || v.specName().contains(t)) {
            return true;
        }
        return v.binds().stream().anyMatch(b -> b.name().contains(t) || (b.tail4() != null && b.tail4().contains(t)));
    }

    /** 该农户绑定的收购规则（含已停用的，由 enabled 字段区分）。 */
    public List<RuleView> farmerRules(long vendorId) {
        requireVendor(vendorId);
        List<Long> ids = jdbc.queryForList("SELECT r.id FROM pricing_rule_bind b JOIN pricing_rule r ON r.id = b.rule_id "
                + "WHERE b.target_id = :v AND r.kind = 'BUY' ORDER BY r.id", Map.of("v", vendorId), Long.class);
        return ids.isEmpty() ? List.of() : views(rules.selectBatchIds(ids));
    }

    private RuleView view(long id) {
        return views(List.of(rules.selectById(id))).get(0);
    }

    private List<RuleView> views(List<PricingRule> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ruleIds = list.stream().map(r -> r.id).toList();
        Map<Long, List<Long>> bound = new HashMap<>();
        for (PricingRuleBind b : binds.selectList(new QueryWrapper<PricingRuleBind>().in("rule_id", ruleIds))) {
            bound.computeIfAbsent(b.ruleId, k -> new ArrayList<>()).add(b.targetId);
        }
        Map<Long, Target> vendors = targets("vendor", true, ids(list, bound, "BUY"));
        Map<Long, Target> customers = targets("customer", false, ids(list, bound, "SELL"));
        Map<Long, Target> products = targets("product", false, list.stream().map(r -> r.productId).toList());
        Map<Long, String> specNames = specs.list().stream().collect(Collectors.toMap(s -> s.id, s -> s.name));

        List<RuleView> out = new ArrayList<>();
        for (PricingRule r : list) {
            Map<Long, Target> pool = r.kind.equals("BUY") ? vendors : customers;
            List<Target> ts = bound.getOrDefault(r.id, List.of()).stream().map(pool::get)
                    .filter(t -> t != null).sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
            out.add(new RuleView(r.id, r.name, r.kind, r.productId, products.get(r.productId).name(), r.specId,
                    specNames.get(r.specId), r.coef, r.enabled, ts, ts.size()));
        }
        return out;
    }

    private static List<Long> ids(List<PricingRule> list, Map<Long, List<Long>> bound, String kind) {
        return list.stream().filter(r -> r.kind.equals(kind)).flatMap(r -> bound.getOrDefault(r.id, List.of()).stream())
                .distinct().toList();
    }

    private Map<Long, Target> targets(String table, boolean withTail, Collection<Long> ids) {
        Map<Long, Target> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String tail = withTail
                ? "(SELECT p.tail4 FROM vendor_phone p WHERE p.vendor_id = t.id ORDER BY p.id LIMIT 1)" : "NULL";
        jdbc.query("SELECT t.id, t.name, " + tail + " AS tail4 FROM " + table + " t WHERE t.id IN (:ids)",
                new MapSqlParameterSource("ids", ids), rs -> {
                    out.put(rs.getLong("id"), new Target(rs.getLong("id"), rs.getString("name"), rs.getString("tail4")));
                });
        return out;
    }

    // ---------------------------------------------------------------- 新建、修改

    @Transactional
    public RuleView create(Draft d, Long operatorId) {
        PricingRule r = new PricingRule();
        r.name = d.name().trim();
        r.kind = d.kind();
        r.productId = d.productId();
        r.specId = d.specId();
        r.coef = d.coef();
        r.enabled = d.enabled() == null || d.enabled();
        return save(r, d.bindIds() == null ? List.of() : dedupe(d.bindIds()), operatorId);
    }

    @Transactional
    public RuleView update(long id, Draft d, Long operatorId) {
        PricingRule r = rules.selectById(id);
        if (r == null) {
            throw ApiException.invalid("规则不存在");
        }
        List<Long> newBinds = d.bindIds() == null ? null : dedupe(d.bindIds());
        if (d.kind() != null && !d.kind().equals(r.kind)) {
            // 收购规则绑农户、发货规则绑客户，换了属性原来的绑定对象就不适用了
            r.kind = d.kind();
            if (newBinds == null) {
                newBinds = List.of();
            }
        }
        if (d.name() != null) {
            r.name = d.name().trim();
        }
        if (d.productId() != null) {
            r.productId = d.productId();
        }
        if (d.specId() != null) {
            r.specId = d.specId();
        }
        if (d.coef() != null) {
            r.coef = d.coef();
        }
        if (d.enabled() != null) {
            r.enabled = d.enabled();
        }
        return save(r, newBinds, operatorId);
    }

    /** @param newBinds null 表示不动现有绑定（但冲突仍按现有绑定检查：改规格、启用都可能造成冲突） */
    private RuleView save(PricingRule r, List<Long> newBinds, Long operatorId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id = :id", Map.of("id", r.productId), Integer.class) == 0) {
            throw ApiException.invalid("商品不存在");
        }
        Spec spec = specs.get(r.specId);
        if (newBinds != null) {
            requireTargets(r.kind, newBinds);
        }
        List<Long> effective = newBinds != null ? newBinds : currentBinds(r.id);
        if (r.kind.equals("BUY") && r.enabled && !effective.isEmpty()) {
            List<Conflict> conflicts = conflicts(r.id == null ? -1 : r.id, r.productId, r.specId, effective);
            if (!conflicts.isEmpty()) {
                throw conflict(conflicts, spec);
            }
        }
        r.updatedBy = operatorId;
        r.updatedAt = LocalDateTime.now();
        if (r.id == null) {
            rules.insert(r);
        } else {
            rules.updateById(r);
        }
        if (newBinds != null) {
            binds.delete(new QueryWrapper<PricingRuleBind>().eq("rule_id", r.id));
            for (Long t : newBinds) {
                binds.insert(new PricingRuleBind(r.id, t));
            }
        }
        return view(r.id);
    }

    private static List<Long> dedupe(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private List<Long> currentBinds(Long ruleId) {
        if (ruleId == null) {
            return List.of();
        }
        return binds.selectList(new QueryWrapper<PricingRuleBind>().eq("rule_id", ruleId)).stream()
                .map(b -> b.targetId).toList();
    }

    private void requireTargets(String kind, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        boolean buy = kind.equals("BUY");
        Map<Long, Target> found = targets(buy ? "vendor" : "customer", false, ids);
        if (found.size() != ids.size()) {
            throw ApiException.invalid(buy ? "绑定的农户不存在" : "绑定的客户不存在");
        }
    }

    // ---------------------------------------------------------------- 冲突

    /** 这些农户在同一商品规格上，已经被别的启用的收购规则占着。 */
    private List<Conflict> conflicts(long exceptRuleId, long productId, long specId, Collection<Long> vendorIds) {
        return jdbc.query("SELECT v.id AS vendor_id, v.name AS vendor_name, r.id AS rule_id, r.name AS rule_name "
                + "FROM pricing_rule_bind b JOIN pricing_rule r ON r.id = b.rule_id JOIN vendor v ON v.id = b.target_id "
                + "WHERE r.kind = 'BUY' AND r.enabled = 1 AND r.product_id = :p AND r.spec_id = :s AND r.id <> :rid "
                + "AND b.target_id IN (:ids) ORDER BY b.target_id, r.id",
                new MapSqlParameterSource().addValue("p", productId).addValue("s", specId)
                        .addValue("rid", exceptRuleId).addValue("ids", vendorIds),
                (rs, i) -> new Conflict(rs.getLong("vendor_id"), rs.getString("vendor_name"), rs.getLong("rule_id"),
                        rs.getString("rule_name")));
    }

    private static ApiException conflict(List<Conflict> cs, Spec spec) {
        String detail = cs.stream().map(c -> c.vendorName() + " 已绑定「" + c.ruleName() + "」")
                .collect(Collectors.joining("；"));
        return new ApiException(ErrorCode.RULE_CONFLICT,
                detail + "。同一农户在同一商品、同一规格（" + spec.name + "）上只能绑一条收购规则，请先从那条规则解绑", cs);
    }

    // ---------------------------------------------------------------- 按农户设置绑定

    /**
     * 把这个农户在“商品 + 规格”上的收购规则设为 ruleId；ruleId 为空就是解绑。
     * 先从这个商品规格下所有收购规则里摘掉他，再绑上指定的，所以不会产生冲突。
     *
     * @return 该农户现在的全部收购规则
     */
    @Transactional
    public List<RuleView> bindFarmer(long vendorId, long productId, long specId, Long ruleId) {
        requireVendor(vendorId);
        if (ruleId != null) {
            PricingRule r = rules.selectById(ruleId);
            if (r == null) {
                throw ApiException.invalid("规则不存在");
            }
            if (!r.kind.equals("BUY")) {
                throw ApiException.invalid("发货规则不能绑给农户");
            }
            if (r.productId != productId || r.specId != specId) {
                throw ApiException.invalid("规则的商品或规格与要绑定的不一致");
            }
            if (!r.enabled) {
                throw ApiException.invalid("这条规则已停用，请先启用");
            }
        }
        jdbc.update("DELETE b FROM pricing_rule_bind b JOIN pricing_rule r ON r.id = b.rule_id "
                + "WHERE b.target_id = :v AND r.kind = 'BUY' AND r.product_id = :p AND r.spec_id = :s",
                new MapSqlParameterSource().addValue("v", vendorId).addValue("p", productId).addValue("s", specId));
        if (ruleId != null) {
            binds.insert(new PricingRuleBind(ruleId, vendorId));
        }
        return farmerRules(vendorId);
    }

    private void requireVendor(long vendorId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM vendor WHERE id = :id", Map.of("id", vendorId), Integer.class) == 0) {
            throw ApiException.invalid("农户不存在");
        }
    }
}
