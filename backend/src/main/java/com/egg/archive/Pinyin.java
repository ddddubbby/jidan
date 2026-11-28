package com.egg.archive;

import net.sourceforge.pinyin4j.PinyinHelper;

/** 名称的拼音首字母，APP 按名称找农户用。 */
final class Pinyin {

    private Pinyin() {
    }

    /**
     * 每个汉字取第一个读音的首字母，字母与数字原样保留（转小写），其余字符跳过。
     * 多音字只取第一个读音，个别名称可能对不上，这时仍可输汉字找。
     */
    static String initials(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c < 128) {
                if (Character.isLetterOrDigit(c)) {
                    out.append(Character.toLowerCase(c));
                }
                continue;
            }
            String[] py = PinyinHelper.toHanyuPinyinStringArray(c);
            if (py != null && py.length > 0 && !py[0].isEmpty()) {
                out.append(py[0].charAt(0));
            }
        }
        return out.toString();
    }
}
