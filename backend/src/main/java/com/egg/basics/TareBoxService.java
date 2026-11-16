package com.egg.basics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.common.ApiException;

import org.springframework.stereotype.Service;

@Service
public class TareBoxService {

    private final TareBoxMapper mapper;

    public TareBoxService(TareBoxMapper mapper) {
        this.mapper = mapper;
    }

    /** 含已停用的，页面上有启用开关。 */
    public List<TareBox> list() {
        return mapper.selectList(new QueryWrapper<TareBox>().orderByAsc("id"));
    }

    /** APP 皮重选项：启用且配成热键的。 */
    public List<TareBox> listHotkeys() {
        return mapper.selectList(new QueryWrapper<TareBox>().eq("enabled", true).eq("hotkey", true).orderByAsc("id"));
    }

    public TareBox create(String name, BigDecimal tareKg, Boolean hotkey, Long operatorId) {
        TareBox t = new TareBox();
        t.name = name.trim();
        t.tareKg = tareKg;
        t.hotkey = hotkey == null || hotkey; // 新增的包装箱默认就是热键（原型 basicSave）
        t.enabled = true;
        t.updatedBy = operatorId;
        t.updatedAt = LocalDateTime.now();
        mapper.insert(t);
        return t;
    }

    /** 只改传了的字段。 */
    public TareBox update(long id, String name, BigDecimal tareKg, Boolean hotkey, Boolean enabled, Long operatorId) {
        TareBox t = mapper.selectById(id);
        if (t == null) {
            throw ApiException.invalid("包装箱不存在");
        }
        if (name != null) {
            t.name = name.trim();
        }
        if (tareKg != null) {
            t.tareKg = tareKg;
        }
        if (hotkey != null) {
            t.hotkey = hotkey;
        }
        if (enabled != null) {
            t.enabled = enabled;
        }
        t.updatedBy = operatorId;
        t.updatedAt = LocalDateTime.now();
        mapper.updateById(t);
        return t;
    }
}
