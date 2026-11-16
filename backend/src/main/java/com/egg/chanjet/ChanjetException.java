package com.egg.chanjet;

/** 调好生意失败。message 直接给人看（写中文，带上好生意返回的原因）。 */
public class ChanjetException extends RuntimeException {

    public ChanjetException(String message) {
        super(message);
    }

    /** 好生意明确拒绝：好生意侧没有生成单据，可以放心重新回传。 */
    public static class Rejected extends ChanjetException {
        public Rejected(String message) {
            super(message);
        }
    }

    /** 超时或连接中断：好生意侧可能已经生成了单据，要管理员先去核对（开发规格 3.4）。 */
    public static class NoReply extends ChanjetException {
        public NoReply(String message) {
            super(message);
        }
    }
}
