package com.egg.basics;

import com.egg.common.CurrentUser;
import java.util.List;
import java.util.Map;

import com.egg.basics.OptionService.OptionView;
import com.egg.basics.Options.Group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置（业务设置那一组，v7 WEB-09）与高级选项（设备与接口适配、作业策略两组）。
 * 两个页面共用一套注册表与 option 表，接口只是按组分开：系统设置页改不到高级选项，反之亦然。
 */
@RestController
public class OptionsController {

    public record OptionList(List<OptionView> items) {
    }

    /** values 里值为 null 的项还原为默认值；没写到的项不动。 */
    public record OptionsForm(@NotNull(message = "没有要修改的内容") Map<String, String> values) {
    }

    private final OptionService service;

    public OptionsController(OptionService service) {
        this.service = service;
    }

    @GetMapping("/api/settings")
    public OptionList settings() {
        return new OptionList(service.view(Group.SETTING));
    }

    @PutMapping("/api/settings")
    public OptionList updateSettings(@Valid @RequestBody OptionsForm form, CurrentUser me) {
        service.update(form.values(), me.id(), Group.SETTING);
        return settings();
    }

    @GetMapping("/api/options")
    public OptionList options() {
        return new OptionList(service.view(Group.ADAPTER, Group.POLICY));
    }

    @PutMapping("/api/options")
    public OptionList updateOptions(@Valid @RequestBody OptionsForm form, CurrentUser me) {
        service.update(form.values(), me.id(), Group.ADAPTER, Group.POLICY);
        return options();
    }
}
