package com.egg.archive;

import java.util.List;

import com.egg.archive.ArchiveService.Farmer;
import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 建单选农户（v7 APP-02）。 */
@RestController
public class FarmerController {

    private final ArchiveService service;

    public FarmerController(ArchiveService service) {
        this.service = service;
    }

    /**
     * 找农户。给 tail：按手机后四位查，返回匹配到的号码；给 q：按名称、编码、号码或拼音首字母查（好生意里没填号码的农户靠这个）。
     * 没有匹配返回空列表。
     */
    @Access(Who.ADMIN_OR_SCALE)
    @GetMapping("/api/farmers")
    public List<Farmer> find(@RequestParam(required = false) String tail, @RequestParam(required = false) String q) {
        if (q != null && tail == null) {
            return service.farmersByName(q);
        }
        return service.farmersByTail(tail);
    }

    /** 当前磅秤员最近收过的 5 位农户。 */
    @Access(Who.SCALE)
    @GetMapping("/api/farmers/recent")
    public List<Farmer> recent(CurrentUser me) {
        return service.recentFarmers(me.id());
    }
}
