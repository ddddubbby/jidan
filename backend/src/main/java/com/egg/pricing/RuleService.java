package com.egg.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.basics.Spec;
import com.egg.basics.SpecService;
import com.egg.basics.TareBox;
import com.egg.basics.TareBoxMapper;
import com.egg.common.ApiException;
import com.egg.common.ErrorCode;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定价规则的新建、修改、筛选（v7 WEB-05、APP-09，按附录 E R-1 行改版）。
 * <p>
 * 规则不再绑农户或客户：建单时按名称直接选，所以名称不能重复，在这里保存时检查（开发规格 5.1）。
 * 一条规则打包了商品、规格、包装箱（皮重）与每箱损耗调整。
 */
@Service
public class RuleService {

    /**
     * @param tareBoxId 包装箱；改版前建的规则可能还没有，为 null
     * @param lossJin   每箱损耗调整（斤），正数加、负数减
     */
    public record RuleView(long id, String name, String kind, long productId, String productName, long specId,
            String specName, BigDecimal stdNetJin, Long tareBoxId, String tareName, BigDecimal tareJin,
            BigDecimal lossJin, BigDecimal coef, boolean enabled) {
    }

    /** 与要保存的规则重名的那一条。 */
    public record Conflict(long ruleId, String ruleName) {
    }

    /** 新建与修改共用；修改时 null 的字段不动。 */
    public record Draft(String name, String kind, Long productId, Long specId, Long tareBoxId, BigDecimal lossJin,
            BigDecimal coef, Boolean enabled) {
    }

    private final PricingRuleMapper rules;
    private final SpecService specs;
    private final TareBoxMapper tares;
    private final NamedParameterJdbcTemplate jdbc;

    public RuleService(PricingRuleMapper rules, SpecService specs, TareBoxMapper tares, NamedParameterJdbcTemplate jdbc) {
        this.rules = rules;
        this.specs = specs;
        this.tares = tares;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 查询

    /**
     * q 按空格拆词，每个词都要命中规则名、商品、规格、包装箱的名称之一。
     *
     * @param enabled 为 true 只列启用的（建单选规则用）
     */
    public List<RuleView> list(String kind, Long productId, Long specId, Boolean enabled, String q) {
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
        if (Boolean.TRUE.equals(enabled)) {
            w.eq("enabled", true);
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
        return v.name().contains(t) || v.productName().contains(t) || v.specName().contains(t)
                || (v.tareName() != null && v.tareName().contains(t));
    }

    public RuleView get(long id) {
        PricingRule r = rules.selectById(id);
        if (r == null) {
            throw ApiException.invalid("规则不存在");
        }
        return views(List.of(r)).get(0);
    }

    /** 建单、改单时用：必须是收购规则。停用的规则不能用来新建，但已经选了它的单照常能改其他内容。 */
    public PricingRule requireBuyRule(long id) {
        PricingRule r = rules.selectById(id);
        if (r == null) {
            throw ApiException.invalid("规则不存在");
        }
        if (!r.kind.equals("BUY")) {
            throw ApiException.invalid("发货规则不能用于采购入库单");
        }
        return r;
    }

    private List<RuleView> views(List<PricingRule> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> products = names("product", list.stream().map(r -> r.productId).distinct().toList());
        Map<Long, Spec> specById = specs.list().stream().collect(Collectors.toMap(s -> s.id, s -> s));
        Map<Long, TareBox> tareById = tares.selectList(null).stream().collect(Collectors.toMap(t -> t.id, t -> t));
        return list.stream().map(r -> {
            Spec s = specById.get(r.specId);
            TareBox t = r.tareBoxId == null ? null : tareById.get(r.tareBoxId);
            return new RuleView(r.id, r.name, r.kind, r.productId, products.get(r.productId), r.specId, s.name,
                    s.stdNetJin, r.tareBoxId, t == null ? null : t.name, t == null ? null : t.tareJin, r.lossJin,
                    r.coef, r.enabled);
        }).toList();
    }

    private Map<Long, String> names(String table, List<Long> ids) {
        return jdbc.query("SELECT id, name FROM " + table + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids),
                rs -> {
                    Map<Long, String> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getLong("id"), rs.getString("name"));
                    }
                    return out;
                });
    }

    // ---------------------------------------------------------------- 新建、修改

    @Transactional
    public RuleView create(Draft d, Long operatorId) {
        PricingRule r = new PricingRule();
        r.name = d.name().trim();
        r.kind = d.kind();
        r.productId = d.productId();
        r.specId = d.specId();
        r.tareBoxId = d.tareBoxId();
        r.lossJin = d.lossJin() == null ? BigDecimal.ZERO : d.lossJin();
        r.coef = d.coef();
        r.enabled = d.enabled() == null || d.enabled();
        return save(r, operatorId);
    }

    @Transactional
    public RuleView update(long id, Draft d, Long operatorId) {
        PricingRule r = rules.selectById(id);
        if (r == null) {
            throw ApiException.invalid("规则不存在");
        }
        if (d.name() != null) {
            r.name = d.name().trim();
        }
        if (d.kind() != null) {
            r.kind = d.kind();
        }
        if (d.productId() != null) {
            r.productId = d.productId();
        }
        if (d.specId() != null) {
            r.specId = d.specId();
        }
        if (d.tareBoxId() != null) {
            r.tareBoxId = d.tareBoxId();
        }
        if (d.lossJin() != null) {
            r.lossJin = d.lossJin();
        }
        if (d.coef() != null) {
            r.coef = d.coef();
        }
        if (d.enabled() != null) {
            r.enabled = d.enabled();
        }
        return save(r, operatorId);
    }

    private RuleView save(PricingRule r, Long operatorId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id = :id", Map.of("id", r.productId), Integer.class) == 0) {
            throw ApiException.invalid("商品不存在");
        }
        specs.get(r.specId);
        if (r.tareBoxId != null && tares.selectById(r.tareBoxId) == null) {
            throw ApiException.invalid("包装箱不存在");
        }
        PricingRule same = rules.selectOne(new QueryWrapper<PricingRule>().eq("name", r.name)
                .ne(r.id != null, "id", r.id).last("LIMIT 1"));
        if (same != null) {
            throw new ApiException(ErrorCode.RULE_CONFLICT,
                    "已有同名规则「" + same.name + "」。建单时按名称选规则，名称不能重复",
                    List.of(new Conflict(same.id, same.name)));
        }
        r.updatedBy = operatorId;
        r.updatedAt = LocalDateTime.now();
        if (r.id == null) {
            rules.insert(r);
        } else {
            rules.updateById(r);
        }
        return get(r.id);
    }
}
