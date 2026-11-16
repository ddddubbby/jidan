package com.egg.archive;

import com.egg.archive.ArchiveService.ArchiveRow;
import com.egg.archive.ArchiveService.Status;
import com.egg.common.PageResult;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 好生意档案同步与查询（v7 WEB-01），仅管理员。 */
@RestController
public class ArchiveController {

    private final ArchiveService service;

    public ArchiveController(ArchiveService service) {
        this.service = service;
    }

    /** 从好生意同步四类档案；失败时返回好生意给的原因，库里保持上次的数据。 */
    @PostMapping("/api/archive/sync")
    public Status sync() {
        return service.sync();
    }

    @GetMapping("/api/archive/status")
    public Status status() {
        return service.status();
    }

    @GetMapping("/api/archive/{type}")
    public PageResult<ArchiveRow> search(@PathVariable String type, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.search(type, q, page, pageSize);
    }
}
