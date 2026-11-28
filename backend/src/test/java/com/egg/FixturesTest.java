package com.egg;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 算例文件（开发规格 5.3）能解析、必需字段齐全。后端与 APP 读仓库根 fixtures/ 里的同一份，不各存副本。
 * 计价、备注等逻辑落地后（P2-1、P2-4）再在对应测试里逐条跑这些算例；这里只管文件本身。
 */
class FixturesTest {

    private static final Path DIR = Path.of("..", "fixtures");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode load(String name) throws IOException {
        return JSON.readTree(DIR.resolve(name).toFile());
    }

    /** 收集缺失或类型不对的字段路径，一次报全，而不是遇到第一个就停。 */
    private static final class Check {
        final List<String> problems = new ArrayList<>();

        void number(JsonNode n, String path, String field) {
            if (!n.path(field).isNumber()) problems.add(path + "." + field + " 应为数字");
        }

        void text(JsonNode n, String path, String field) {
            if (!n.path(field).isTextual() || n.path(field).asText().isEmpty()) problems.add(path + "." + field + " 应为非空文本");
        }

        void bool(JsonNode n, String path, String field) {
            if (!n.path(field).isBoolean()) problems.add(path + "." + field + " 应为布尔值");
        }

        void object(JsonNode n, String path, String field) {
            if (!n.path(field).isObject()) problems.add(path + "." + field + " 应为对象");
        }

        JsonNode nonEmptyArray(JsonNode n, String path, String field) {
            JsonNode a = n.path(field);
            if (!a.isArray() || a.isEmpty()) {
                problems.add(path + "." + field + " 应为非空数组");
                return JSON.createArrayNode();
            }
            return a;
        }

        /** 每个算例都要有 id、来源、说明。 */
        void caseHead(JsonNode c, String path) {
            text(c, path, "id");
            text(c, path, "source");
            text(c, path, "desc");
        }
    }

    @Test
    void pricingParsesWithRequiredFields() throws IOException {
        JsonNode root = load("pricing.json");
        Check ck = new Check();
        JsonNode cases = ck.nonEmptyArray(root, "pricing", "cases");
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String p = "pricing.cases[" + i + "]";
            ck.caseHead(c, p);
            ck.object(c, p, "input");
            ck.nonEmptyArray(c.path("input"), p + ".input", "boxNetJin");
            for (String f : List.of("standardNetJin", "basePrice", "coefficient")) ck.number(c.path("input"), p + ".input", f);
            ck.text(c.path("input"), p + ".input", "rounding");
            ck.object(c, p, "expected");
            for (String f : List.of("totalJin", "rawAvgJin", "avgJin", "tailJin", "priceDiff", "unitPrice", "amount")) {
                ck.number(c.path("expected"), p + ".expected", f);
            }
        }
        JsonNode table = ck.nonEmptyArray(root, "pricing", "roundingTable");
        for (int i = 0; i < table.size(); i++) {
            for (String f : List.of("boxCount", "totalJin", "rawAvgJin", "half", "floor", "ceil")) {
                ck.number(table.get(i), "pricing.roundingTable[" + i + "]", f);
            }
        }
        assertThat(ck.problems).isEmpty();
    }

    @Test
    void detectKParsesWithRequiredFields() throws IOException {
        JsonNode root = load("detect-k.json");
        Check ck = new Check();
        JsonNode cases = ck.nonEmptyArray(root, "detect-k", "cases");
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String p = "detect-k.cases[" + i + "]";
            ck.caseHead(c, p);
            ck.object(c, p, "input");
            for (String f : List.of("grossJin", "tareJin", "standardNetJin", "tolerancePct", "maxK")) ck.number(c.path("input"), p + ".input", f);
            ck.object(c, p, "expected");
            JsonNode e = c.path("expected");
            for (String f : List.of("k", "tareTotalJin", "netTotalJin")) ck.number(e, p + ".expected", f);
            ck.bool(e, p + ".expected", "ambiguous");
            // 识别不准时给两个候选箱数，否则必须明确为 null
            if (e.path("ambiguous").asBoolean()) {
                if (!e.path("candidates").isArray() || e.path("candidates").size() != 2) ck.problems.add(p + ".expected.candidates 应为两个箱数");
            } else if (!e.path("candidates").isNull()) {
                ck.problems.add(p + ".expected.candidates 应为 null");
            }
        }
        assertThat(ck.problems).isEmpty();
    }

    @Test
    void weighingParsesWithRequiredFields() throws IOException {
        JsonNode root = load("weighing.json");
        Check ck = new Check();
        JsonNode cases = ck.nonEmptyArray(root, "weighing", "cases");
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String p = "weighing.cases[" + i + "]";
            ck.caseHead(c, p);
            ck.object(c, p, "setup");
            ck.number(c.path("setup"), p + ".setup", "tareJin");
            ck.number(c.path("setup"), p + ".setup", "standardNetJin");
            ck.object(c.path("setup"), p + ".setup", "options");
            JsonNode events = ck.nonEmptyArray(c, p, "events");
            for (int j = 0; j < events.size(); j++) {
                ck.number(events.get(j), p + ".events[" + j + "]", "grossJin");
                ck.bool(events.get(j), p + ".events[" + j + "]", "stable");
            }
            ck.object(c, p, "expected");
            JsonNode records = c.path("expected").path("records");
            if (!records.isArray()) ck.problems.add(p + ".expected.records 应为数组");
            for (int j = 0; j < records.size(); j++) {
                for (String f : List.of("seq", "group", "k", "netJin", "tareJin", "grossJin", "atEvent")) {
                    ck.number(records.get(j), p + ".expected.records[" + j + "]", f);
                }
            }
        }
        assertThat(ck.problems).isEmpty();
    }

    @Test
    void remarkParsesWithRequiredFields() throws IOException {
        JsonNode root = load("remark.json");
        Check ck = new Check();
        JsonNode cases = ck.nonEmptyArray(root, "remark", "cases");
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String p = "remark.cases[" + i + "]";
            ck.caseHead(c, p);
            ck.object(c, p, "input");
            ck.nonEmptyArray(c.path("input"), p + ".input", "boxNetJin");
            ck.number(c.path("input"), p + ".input", "maxLength");
            ck.object(c, p, "expected");
            ck.text(c.path("expected"), p + ".expected", "text");
            ck.bool(c.path("expected"), p + ".expected", "truncated");
            JsonNode groups = ck.nonEmptyArray(c.path("expected"), p + ".expected", "groups");
            for (int j = 0; j < groups.size(); j++) {
                ck.number(groups.get(j), p + ".expected.groups[" + j + "]", "jin");
                ck.number(groups.get(j), p + ".expected.groups[" + j + "]", "boxes");
            }
        }
        assertThat(ck.problems).isEmpty();
    }
}
