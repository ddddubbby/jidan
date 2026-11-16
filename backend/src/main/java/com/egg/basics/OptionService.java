package com.egg.basics;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.egg.basics.Options.Choice;
import com.egg.basics.Options.Def;
import com.egg.basics.Options.Group;
import com.egg.common.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读写 option 表，定义与默认值取自注册表 {@link Options}。不做缓存：改完下一次读就是新值，
 * 所以“改选项不需要重启”（开发规格 4.4）。
 */
@Service
public class OptionService {

    /** 一个选项的定义加当前值，系统设置页与高级选项页都按它渲染。 */
    public record OptionView(String key, String group, String groupName, String type, String name, String hint,
            List<Choice> choices, String min, String max, String defaultValue, String value) {
    }

    private final OptionMapper mapper;

    public OptionService(OptionMapper mapper) {
        this.mapper = mapper;
    }

    /** 其他模块读单个选项用：存过的取存的，没存过取默认值。 */
    public String get(String key) {
        Def def = Options.find(key);
        if (def == null) {
            throw new IllegalArgumentException("没有登记的选项：" + key);
        }
        Option row = mapper.selectOne(new QueryWrapper<Option>().eq("opt_key", key));
        return row == null ? def.defaultValue() : row.optValue;
    }

    /** 某几组选项的当前值（含默认），键是选项键。APP 的 bootstrap 用它下发。 */
    public Map<String, String> values(Group... groups) {
        Map<String, String> stored = stored();
        Map<String, String> out = new LinkedHashMap<>();
        for (Def d : Options.of(groups)) {
            out.put(d.key(), stored.getOrDefault(d.key(), d.defaultValue()));
        }
        return out;
    }

    public List<OptionView> view(Group... groups) {
        Map<String, String> stored = stored();
        return Options.of(groups).stream().map(d -> new OptionView(d.key(), d.group().name(), d.group().label,
                d.type().name(), d.name(), d.hint(), d.choices(), d.min(), d.max(), d.defaultValue(),
                stored.getOrDefault(d.key(), d.defaultValue()))).toList();
    }

    /**
     * 批量修改，全成功或全不改。值为 null 表示还原为默认值。
     * 只许改 {@code allowed} 这几组里的选项：系统设置与高级选项是两个页面，各管各的。
     */
    @Transactional
    public void update(Map<String, String> values, Long operatorId, Group... allowed) {
        List<Group> groups = List.of(allowed);
        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            Def def = Options.find(e.getKey());
            if (def == null || !groups.contains(def.group())) {
                throw ApiException.invalid("这里不能修改的选项：" + e.getKey());
            }
            normalized.put(def.key(), e.getValue() == null ? null : def.normalize(e.getValue()));
        }
        LocalDateTime now = LocalDateTime.now();
        normalized.forEach((key, value) -> {
            if (value == null) {
                mapper.delete(new QueryWrapper<Option>().eq("opt_key", key));
            } else {
                mapper.upsert(key, value, operatorId, now);
            }
        });
    }

    private Map<String, String> stored() {
        Map<String, String> m = new HashMap<>();
        for (Option o : mapper.selectList(null)) {
            m.put(o.optKey, o.optValue);
        }
        return m;
    }
}
