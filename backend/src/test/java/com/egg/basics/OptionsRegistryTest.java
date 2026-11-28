package com.egg.basics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.egg.basics.Options.Def;
import com.egg.basics.Options.Group;
import com.egg.common.ApiException;

import org.junit.jupiter.api.Test;

/** 不需要库：注册表本身自洽，且与开发规格 4.3、4.4 登记的选项逐项对得上。 */
class OptionsRegistryTest {

    @Test
    void everyDefaultPassesItsOwnValidation() {
        for (Def d : Options.of(Group.values())) {
            assertThat(d.normalize(d.defaultValue())).as(d.key()).isEqualTo(d.defaultValue());
        }
    }

    @Test
    void settingGroupIsTheEightSettingsOfWeb09() {
        // 标签纸尺寸占三行，其余各一行
        assertThat(keys(Group.SETTING)).containsExactlyInAnyOrder("round.mode", "print.timing", "invoice.type",
                "invoice.tax-rate", "label.width-mm", "label.height-mm", "label.gap-mm", "label.content",
                "device.scale-model", "device.printer-model");
    }

    @Test
    void advancedOptionsMatchSpecChapter4() {
        assertThat(keys(Group.ADAPTER)).containsExactlyInAnyOrder("scale.unit", "scale.tare-by", "scale.reading-is-net",
                "scale.stable-by", "scale.stable-window-ms", "scale.stable-tolerance-jin", "weigh.empty-below-jin",
                "weigh.merge-tolerance-pct", "weigh.merge-max-k", "print.render", "print.dpi", "print.offset-x-mm",
                "print.offset-y-mm", "push.remark", "push.remark-max-length");
        assertThat(keys(Group.POLICY)).containsExactlyInAnyOrder("weigh.record-by", "weigh.merge-detect",
                "weigh.manual-entry", "print.enabled", "submit.without-price", "push.on-approve");
    }

    @Test
    void defaultsAreTheV7Behaviour() {
        assertThat(def("print.timing").defaultValue()).isEqualTo("per-box");
        assertThat(def("label.content").defaultValue()).isEqualTo("minimal");
        assertThat(def("device.scale-model").choices()).extracting(Options.Choice::value).containsExactly("lanstar-ble");
        assertThat(def("device.printer-model").choices()).extracting(Options.Choice::value)
                .containsExactly("gainscha-gp-m32b");
        assertThat(def("scale.stable-window-ms").defaultValue()).isEqualTo("800");
        // 全系统用斤（附录 E R-1 行）：容差、空秤阈值按斤给，秤的读数默认按公斤换算
        assertThat(def("scale.stable-tolerance-jin").defaultValue()).isEqualTo("0.1");
        assertThat(def("weigh.empty-below-jin").defaultValue()).isEqualTo("10");
        assertThat(def("scale.unit").defaultValue()).isEqualTo("kg");
        assertThat(def("weigh.merge-tolerance-pct").defaultValue()).isEqualTo("10");
        assertThat(def("weigh.merge-max-k").defaultValue()).isEqualTo("8");
        assertThat(def("print.dpi").defaultValue()).isEqualTo("203");
        assertThat(def("submit.without-price").defaultValue()).isEqualTo("block");
        assertThat(def("push.on-approve").defaultValue()).isEqualTo("auto");
    }

    @Test
    void normalizeRejectsValuesOutsideTheDefinition() {
        assertThatThrownBy(() -> def("device.printer-model").normalize("other-brand")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> def("print.dpi").normalize("abc")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> def("print.dpi").normalize("203.5")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> def("print.dpi").normalize("50")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> def("invoice.tax-rate").normalize("101")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> def("label.width-mm").normalize("")).isInstanceOf(ApiException.class);
    }

    @Test
    void normalizeTidiesNumbers() {
        assertThat(def("print.dpi").normalize(" 300 ")).isEqualTo("300");
        assertThat(def("scale.stable-tolerance-jin").normalize("0.050")).isEqualTo("0.05");
        assertThat(def("print.offset-x-mm").normalize("0.0")).isEqualTo("0");
        assertThat(def("print.offset-x-mm").normalize("-1.50")).isEqualTo("-1.5");
    }

    private static List<String> keys(Group g) {
        return Options.of(g).stream().map(Def::key).toList();
    }

    private static Def def(String key) {
        Def d = Options.find(key);
        assertThat(d).as(key).isNotNull();
        return d;
    }

    @Test
    void unknownKeyIsNotRegistered() {
        assertThat(Options.find("no.such")).isNull();
        assertThat(Set.copyOf(keys(Group.SETTING))).doesNotContainAnyElementsOf(keys(Group.ADAPTER));
    }
}
