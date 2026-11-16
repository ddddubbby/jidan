package com.egg.order;

import com.egg.common.CurrentUser;
import com.egg.order.OrderFlowService.Flow;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 单据的审核、回传、退回待审核、标记为已回传、作废（开发规格 5.2）。仅管理员（不标 @Access 即仅管理员）。 */
@RestController
public class OrderFlowController {

    /** @param hsId 好生意单号，可不填 */
    public record MarkPushedForm(String hsId) {
    }

    private final OrderFlowService flow;

    public OrderFlowController(OrderFlowService flow) {
        this.flow = flow;
    }

    /** push.on-approve = auto 时随即回传一次；回传失败不是接口错误，状态仍是 APPROVED 并带 pushError。 */
    @PostMapping("/api/orders/{id}/approve")
    public Flow approve(@PathVariable long id, CurrentUser me) {
        return flow.approve(id, me);
    }

    @PostMapping("/api/orders/{id}/push")
    public Flow push(@PathVariable long id, CurrentUser me) {
        return flow.push(id, me);
    }

    @PostMapping("/api/orders/{id}/unapprove")
    public Flow unapprove(@PathVariable long id, CurrentUser me) {
        return flow.unapprove(id, me);
    }

    @PostMapping("/api/orders/{id}/mark-pushed")
    public Flow markPushed(@PathVariable long id, @RequestBody(required = false) MarkPushedForm form, CurrentUser me) {
        return flow.markPushed(id, form == null ? null : form.hsId(), me);
    }

    @PostMapping("/api/orders/{id}/void")
    public Flow voidOrder(@PathVariable long id, CurrentUser me) {
        return flow.voidOrder(id, me);
    }
}
