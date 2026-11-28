package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.common.ApiException;

import org.springframework.stereotype.Service;

@Service
public class SpecService {

    private final SpecMapper mapper;

    public SpecService(SpecMapper mapper) {
        this.mapper = mapper;
    }

    /** 含已停用的，页面上有启用开关。 */
    public List<Spec> list() {
        return mapper.selectList(new QueryWrapper<Spec>().orderByAsc("id"));
    }

    /** 当前启用的规格，基准价页与 APP 建单用。 */
    public List<Spec> listEnabled() {
        return mapper.selectList(new QueryWrapper<Spec>().eq("enabled", true).orderByAsc("id"));
    }

    public Spec get(long id) {
        Spec s = mapper.selectById(id);
        if (s == null) {
            throw ApiException.invalid("规格不存在");
        }
        return s;
    }

    public Spec create(String name, int eggCount, BigDecimal stdNetJin, Long operatorId) {
        Spec s = new Spec();
        s.name = name.trim();
        s.eggCount = eggCount;
        s.stdNetJin = stdNetJin;
        s.enabled = true;
        s.updatedBy = operatorId;
        s.updatedAt = LocalDateTime.now();
        mapper.insert(s);
        return s;
    }

    /** 只改传了的字段。 */
    public Spec update(long id, String name, Integer eggCount, BigDecimal stdNetJin, Boolean enabled, Long operatorId) {
        Spec s = get(id);
        if (name != null) {
            s.name = name.trim();
        }
        if (eggCount != null) {
            s.eggCount = eggCount;
        }
        if (stdNetJin != null) {
            s.stdNetJin = stdNetJin;
        }
        if (enabled != null) {
            s.enabled = enabled;
        }
        s.updatedBy = operatorId;
        s.updatedAt = LocalDateTime.now();
        mapper.updateById(s);
        return s;
    }
}
