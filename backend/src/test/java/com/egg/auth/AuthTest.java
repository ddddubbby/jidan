package com.egg.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.egg.MySqlTestConfig;
import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * P1-2 验收：登录成功；密码错；管理员登 APP 被拒；停用后旧 token 失效；重置密码后旧 token 失效、新密码可登录。
 * 初始管理员也在这里走：测试库是空的，管理员完全靠 egg.admin.* 建出来，下面的用例都用它登录。
 */
@SpringBootTest(properties = {"egg.admin.phone=13700000001", "egg.admin.password=boss-pass-1", "egg.admin.name=测试管理员"})
@AutoConfigureMockMvc
@Import({MySqlTestConfig.class, AuthTest.Probes.class})
class AuthTest {

    private static final String ADMIN_PHONE = "13700000001";
    private static final String ADMIN_PASSWORD = "boss-pass-1";

    /**
     * 用来验证 @Access 的四种取值与“不标就是仅管理员”。直接嵌套在测试类里，组件扫描会跳过它
     * （否则别的测试的上下文和 OpenAPI 快照里也会多出这几个接口），只靠下面的 @Bean 在本测试里注册。
     */
    @Controller
    @ResponseBody
    static class ProbeController {
        @GetMapping("/api/probe/default")
        String byDefault() {
            return "ok";
        }

        @Access(Who.SCALE)
        @GetMapping("/api/probe/scale")
        String scaleOnly(CurrentUser me) {
            return me.name();
        }

        @Access(Who.ADMIN_OR_SCALE)
        @GetMapping("/api/probe/both")
        String both() {
            return "ok";
        }

        @Access(Who.PUBLIC)
        @GetMapping("/api/probe/public")
        String open() {
            return "ok";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InitialAdmin initialAdmin;

    private ResultActions login(String phone, String password, String client) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("phone", phone, "password", password, "client", client))));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String loginToken(String phone, String password, String client) throws Exception {
        return body(login(phone, password, client).andExpect(status().isOk())).get("token").asText();
    }

    private String adminToken() throws Exception {
        return loginToken(ADMIN_PHONE, ADMIN_PASSWORD, "WEB");
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
    }

    /** 管理员新增一个磅秤员，返回 { id, phone, password }。 */
    private JsonNode newScale(String name, String phone) throws Exception {
        JsonNode r = body(mvc.perform(post("/api/accounts").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", name, "phone", phone))))
                .andExpect(status().isOk()));
        return json.createObjectNode().put("id", r.at("/account/id").asLong()).put("phone", phone)
                .put("password", r.get("password").asText());
    }

    // ------------------------------------------------------------ 登录

    @Test
    void initialAdminIsCreatedFromEnvAndOnlyWhenNoAdminExists() throws Exception {
        JsonNode r = body(login(ADMIN_PHONE, ADMIN_PASSWORD, "WEB").andExpect(status().isOk()));
        assertThat(r.get("name").asText()).isEqualTo("测试管理员");
        assertThat(r.get("role").asText()).isEqualTo("ADMIN");
        assertThat(r.get("token").asText()).hasSize(64);

        // 库里已有管理员：再跑一次不会重复创建，也不会覆盖密码
        initialAdmin.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
        login(ADMIN_PHONE, ADMIN_PASSWORD, "WEB").andExpect(status().isOk());
        // 密码只存 BCrypt
        String hash = jdbc.queryForObject("SELECT password_hash FROM account WHERE phone = ?", String.class, ADMIN_PHONE);
        assertThat(new BCryptPasswordEncoder().matches(ADMIN_PASSWORD, hash)).isTrue();
    }

    @Test
    void scaleOperatorLogsInToApp() throws Exception {
        JsonNode acc = newScale("李师傅", "13700000011");
        JsonNode r = body(login("13700000011", acc.get("password").asText(), "APP").andExpect(status().isOk()));
        assertThat(r.get("name").asText()).isEqualTo("李师傅");
        assertThat(r.get("role").asText()).isEqualTo("SCALE");

        me(r.get("token").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("李师傅"))
                .andExpect(jsonPath("$.phone").value("13700000011"))
                .andExpect(jsonPath("$.role").value("SCALE"));
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM account WHERE phone = '13700000011'", Object.class)).isNotNull();
    }

    @Test
    void wrongPasswordOrUnknownPhoneIsBadCredentials() throws Exception {
        JsonNode acc = newScale("李师傅", "13700000012");
        login("13700000012", "不对的密码", "APP").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("手机号或密码不对"));
        login("13799999999", acc.get("password").asText(), "APP").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("手机号或密码不对"));
    }

    @Test
    void adminCannotLogIntoAppAndScaleCannotLogIntoWeb() throws Exception {
        login(ADMIN_PHONE, ADMIN_PASSWORD, "APP").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_CLIENT"))
                .andExpect(jsonPath("$.message").value("这个账号只能用 Web 管理端"));
        JsonNode acc = newScale("李师傅", "13700000013");
        login("13700000013", acc.get("password").asText(), "WEB").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_CLIENT"));
        // 管理员密码不对时仍然是“手机号或密码不对”，不暴露这个手机号是管理员
        login(ADMIN_PHONE, "不对", "APP").andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void sameAccountSharesOneTokenAcrossDevices() throws Exception {
        JsonNode acc = newScale("李师傅", "13700000014");
        String a = loginToken("13700000014", acc.get("password").asText(), "APP");
        String b = loginToken("13700000014", acc.get("password").asText(), "APP");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void loginRequestIsValidated() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"\",\"password\":\"x\",\"client\":\"APP\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"))
                .andExpect(jsonPath("$.message").value("填写手机号"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"1\",\"password\":\"x\",\"client\":\"PC\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"));
    }

    // ------------------------------------------------------------ 拦截与角色

    @Test
    void missingOrUnknownTokenIsUnauthenticated() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        me("随便编的").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndLoginNeedNoToken() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/probe/public")).andExpect(status().isOk());
    }

    @Test
    void unknownPathStillReturnsNotFoundWithoutLogin() throws Exception {
        mvc.perform(get("/api/no-such-thing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void accessAnnotationDecidesWhoCanCall() throws Exception {
        JsonNode acc = newScale("李师傅", "13700000015");
        String scale = loginToken("13700000015", acc.get("password").asText(), "APP");
        String admin = adminToken();

        // 不标 @Access：仅管理员
        mvc.perform(get("/api/probe/default").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get("/api/probe/default").header("Authorization", "Bearer " + scale)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_CLIENT"));
        // 仅磅秤员，且控制器拿得到当前登录人
        mvc.perform(get("/api/probe/scale").header("Authorization", "Bearer " + scale)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("李师傅"));
        mvc.perform(get("/api/probe/scale").header("Authorization", "Bearer " + admin)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_CLIENT"));
        // 两者都可
        mvc.perform(get("/api/probe/both").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get("/api/probe/both").header("Authorization", "Bearer " + scale)).andExpect(status().isOk());
        // 磅秤员不能管账号
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + scale)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ 停用与重置密码

    @Test
    void disablingRevokesOldTokenAndBlocksLoginUntilEnabled() throws Exception {
        JsonNode acc = newScale("李师傅", "13700000016");
        String pw = acc.get("password").asText();
        String old = loginToken("13700000016", pw, "APP");
        me(old).andExpect(status().isOk());

        mvc.perform(put("/api/accounts/" + acc.get("id").asLong() + "/enabled").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        me(old).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        login("13700000016", pw, "APP").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("账号已停用，请联系管理员"));

        mvc.perform(put("/api/accounts/" + acc.get("id").asLong() + "/enabled").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        String fresh = loginToken("13700000016", pw, "APP");
        assertThat(fresh).isNotEqualTo(old);
        me(old).andExpect(status().isUnauthorized());
        me(fresh).andExpect(status().isOk());
    }

    @Test
    void resettingPasswordRevokesOldTokenAndNewPasswordWorks() throws Exception {
        JsonNode acc = newScale("李师傅", "13700000017");
        String oldPw = acc.get("password").asText();
        String old = loginToken("13700000017", oldPw, "APP");

        JsonNode r = body(mvc.perform(post("/api/accounts/" + acc.get("id").asLong() + "/reset-password")
                .header("Authorization", "Bearer " + adminToken())).andExpect(status().isOk()));
        String newPw = r.get("password").asText();
        assertThat(newPw).matches("\\d{6}").isNotEqualTo(oldPw);

        me(old).andExpect(status().isUnauthorized());
        login("13700000017", oldPw, "APP").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        String fresh = loginToken("13700000017", newPw, "APP");
        assertThat(fresh).isNotEqualTo(old);
        me(fresh).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 账号管理

    @Test
    void creatingAccountReturnsOneTimePasswordAndListNeverLeaksSecrets() throws Exception {
        JsonNode r = body(mvc.perform(post("/api/accounts").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 赵师傅 \",\"phone\":\"13700000018\"}"))
                .andExpect(status().isOk()));
        assertThat(r.get("password").asText()).matches("\\d{6}");
        assertThat(r.at("/account/name").asText()).isEqualTo("赵师傅");
        assertThat(r.at("/account/enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM account WHERE phone = '13700000018'", String.class))
                .isNotEqualTo(r.get("password").asText());

        String list = mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(list);
        assertThat(rows).isNotEmpty();
        for (JsonNode row : rows) {
            assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "phone", "enabled", "lastLoginAt");
            assertThat(row.get("phone").asText()).isNotEqualTo(ADMIN_PHONE); // 只列磅秤员
        }
        assertThat(list).contains("13700000018").doesNotContain("password").doesNotContain("token");
    }

    @Test
    void accountInputIsValidated() throws Exception {
        String admin = adminToken();
        JsonNode acc = newScale("李师傅", "13700000019");
        postInvalid(admin, "{\"name\":\"\",\"phone\":\"13700000020\"}", "填写姓名");
        postInvalid(admin, "{\"name\":\"某人\",\"phone\":\"1370000\"}", "手机号要 11 位数字");
        postInvalid(admin, "{\"name\":\"某人\",\"phone\":\"" + acc.get("phone").asText() + "\"}", "这个手机号已有账号");
    }

    private void postInvalid(String token, String content, String message) throws Exception {
        mvc.perform(post("/api/accounts").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"))
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void adminAccountsAreNotManagedHere() throws Exception {
        long adminId = jdbc.queryForObject("SELECT id FROM account WHERE phone = ?", Long.class, ADMIN_PHONE);
        String admin = adminToken();
        mvc.perform(post("/api/accounts/" + adminId + "/reset-password").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"));
        mvc.perform(put("/api/accounts/" + adminId + "/enabled").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/accounts/999999/reset-password").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        me(admin).andExpect(status().isOk());
    }
}
