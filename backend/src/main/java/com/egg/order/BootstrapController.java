package com.egg.order;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.order.BootstrapService.Bootstrap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BootstrapController {

    private final BootstrapService service;

    public BootstrapController(BootstrapService service) {
        this.service = service;
    }

    @Access(Who.SCALE)
    @GetMapping("/api/app/bootstrap")
    public Bootstrap bootstrap() {
        return service.load();
    }
}
