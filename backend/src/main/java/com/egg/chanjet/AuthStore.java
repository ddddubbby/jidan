package com.egg.chanjet;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 安全级别“高”（CHANJET_TOKEN_MODE=ticket）下的换票状态：最新的 appTicket，以及换到的 access/refresh token。
 * 每个 appKey 一行、只覆盖（表 {@code chanjet_auth}，附录 E P8-5）。正式运行用 {@link JdbcAuthStore}，
 * 测试用 {@link #memory()}。
 */
public interface AuthStore {

    /**
     * @param accessUntil  accessToken 用到这个时刻为止（已扣掉提前换的余量）
     * @param refreshUntil refreshToken 用到这个时刻为止（同上）
     */
    record State(String appTicket, Instant ticketAt, String accessToken, Instant accessUntil, String refreshToken,
            Instant refreshUntil) {
        static final State EMPTY = new State(null, null, null, null, null, null);
    }

    /** 没有记录时返回 {@link State#EMPTY}。 */
    State load(String appKey);

    void saveTicket(String appKey, String appTicket, Instant at);

    void clearTicket(String appKey);

    void saveToken(String appKey, String accessToken, Instant accessUntil, String refreshToken, Instant refreshUntil);

    /** 只作废 accessToken；refreshToken 留着。 */
    void clearAccess(String appKey);

    /** 进程内存里的实现，重启即丢；只给测试与没有数据库的场合用。 */
    static AuthStore memory() {
        return new AuthStore() {
            private final Map<String, State> rows = new ConcurrentHashMap<>();

            @Override
            public State load(String appKey) {
                return rows.getOrDefault(appKey, State.EMPTY);
            }

            @Override
            public void saveTicket(String appKey, String appTicket, Instant at) {
                rows.compute(appKey, (k, s) -> {
                    State o = s == null ? State.EMPTY : s;
                    return new State(appTicket, at, o.accessToken(), o.accessUntil(), o.refreshToken(), o.refreshUntil());
                });
            }

            @Override
            public void clearTicket(String appKey) {
                saveTicket(appKey, null, null);
            }

            @Override
            public void saveToken(String appKey, String accessToken, Instant accessUntil, String refreshToken,
                    Instant refreshUntil) {
                rows.compute(appKey, (k, s) -> {
                    State o = s == null ? State.EMPTY : s;
                    return new State(o.appTicket(), o.ticketAt(), accessToken, accessUntil, refreshToken, refreshUntil);
                });
            }

            @Override
            public void clearAccess(String appKey) {
                rows.computeIfPresent(appKey, (k, o) -> new State(o.appTicket(), o.ticketAt(), null, null,
                        o.refreshToken(), o.refreshUntil()));
            }
        };
    }
}
