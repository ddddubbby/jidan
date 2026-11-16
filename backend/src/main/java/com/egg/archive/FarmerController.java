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

    /** 按手机后四位查农户，返回匹配到的号码；没有匹配返回空列表。 */
    @Access(Who.ADMIN_OR_SCALE)
    @GetMapping("/api/farmers")
    public List<Farmer> byTail(@RequestParam(required = false) String tail) {
        return service.farmersByTail(tail);
    }

    /** 当前磅秤员最近收过的 5 位农户。 */
    @Access(Who.SCALE)
    @GetMapping("/api/farmers/recent")
    public List<Farmer> recent(CurrentUser me) {
        return service.recentFarmers(me.id());
    }
}
