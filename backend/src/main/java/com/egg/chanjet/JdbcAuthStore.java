package com.egg.chanjet;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/** {@link AuthStore} 存在表 {@code chanjet_auth} 里（V2），每个 appKey 一行，只覆盖。 */
public class JdbcAuthStore implements AuthStore {

    private final JdbcTemplate jdbc;

    public JdbcAuthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public State load(String appKey) {
        List<State> rows = jdbc.query("SELECT app_ticket, ticket_at, access_token, access_until, refresh_token, refresh_until"
                + " FROM chanjet_auth WHERE app_key = ?",
                (rs, i) -> new State(rs.getString(1), instant(rs.getTimestamp(2)), rs.getString(3),
                        instant(rs.getTimestamp(4)), rs.getString(5), instant(rs.getTimestamp(6))),
                appKey);
        return rows.isEmpty() ? State.EMPTY : rows.get(0);
    }

    @Override
    public void saveTicket(String appKey, String appTicket, Instant at) {
        jdbc.update("INSERT INTO chanjet_auth (app_key, app_ticket, ticket_at) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE app_ticket = VALUES(app_ticket), ticket_at = VALUES(ticket_at)",
                appKey, appTicket, ts(at));
    }

    @Override
    public void clearTicket(String appKey) {
        jdbc.update("UPDATE chanjet_auth SET app_ticket = NULL, ticket_at = NULL WHERE app_key = ?", appKey);
    }

    @Override
    public void saveToken(String appKey, String accessToken, Instant accessUntil, String refreshToken, Instant refreshUntil) {
        jdbc.update("INSERT INTO chanjet_auth (app_key, access_token, access_until, refresh_token, refresh_until)"
                + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE access_token = VALUES(access_token),"
                + " access_until = VALUES(access_until), refresh_token = VALUES(refresh_token),"
                + " refresh_until = VALUES(refresh_until)",
                appKey, accessToken, ts(accessUntil), refreshToken, ts(refreshUntil));
    }

    @Override
    public void clearAccess(String appKey) {
        jdbc.update("UPDATE chanjet_auth SET access_token = NULL, access_until = NULL WHERE app_key = ?", appKey);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
