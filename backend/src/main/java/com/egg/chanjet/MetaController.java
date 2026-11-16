package com.egg.chanjet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {

    /** @param chanjetMode fake / real；Web 据此显示“演示模式” */
    public record Meta(String chanjetMode, String bookName) {
    }

    private final ChanjetClient chanjet;
    private final String mode;

    public MetaController(ChanjetClient chanjet, @Value("${egg.chanjet.mode:fake}") String mode) {
        this.chanjet = chanjet;
        this.mode = mode;
    }

    @GetMapping("/api/meta")
    public Meta meta() {
        return new Meta(mode, chanjet.bookName());
    }
}
