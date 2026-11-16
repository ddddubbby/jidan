package com.egg.chanjet;

import com.egg.chanjet.FakeChanjetClient.Failure;
import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.ApiException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 仅模拟模式的两个失败开关（开发规格 5.2、11.4）。真实模式下调用直接报错。 */
@RestController
public class DevChanjetController {

    /** @param mode reject：下一次回传被拒绝；no-reply：下一次回传没有答复；空或 none：取消 */
    public record FailForm(String mode) {
    }

    /** @param pending 开关现在的状态：reject / no-reply / none */
    public record FailState(String pending) {
    }

    private final ChanjetClient chanjet;

    public DevChanjetController(ChanjetClient chanjet) {
        this.chanjet = chanjet;
    }

    @Access(Who.ADMIN)
    @PostMapping("/api/dev/chanjet/fail-next")
    public FailState failNext(@RequestBody FailForm form) {
        if (!(chanjet instanceof FakeChanjetClient fake)) {
            throw ApiException.invalid("只有演示模式（模拟好生意）才有失败开关");
        }
        String mode = form.mode() == null ? "none" : form.mode();
        switch (mode) {
            case "reject" -> fake.failNext(Failure.REJECT);
            case "no-reply" -> fake.failNext(Failure.NO_REPLY);
            case "none" -> fake.failNext(null);
            default -> throw ApiException.invalid("mode 只能是 reject、no-reply 或 none");
        }
        return new FailState(mode);
    }
}
