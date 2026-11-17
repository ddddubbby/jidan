# Inter（数字字体）

APP 里的数字用 Inter，等宽数字（`tnum`）。与 Web 同一套数字排版（开发规格附录 E，F4-2 与 F4-3 两行）：只数字和少数符号用 Inter，汉字和字母落到系统字体。

- 许可：SIL Open Font License 1.1，全文见同目录 `OFL.txt`（版权：The Inter Project Authors）。Inter 没有保留字体名。
- 来源：Inter 4.0 的 `Inter-Medium.otf`、`Inter-SemiBold.otf`、`Inter-Bold.otf`（OpenType/CFF，Android 的 `res/font` 可直接用；woff2 不行）。
- 放在 `src/main/res/font/inter_medium.otf`（500）、`inter_semibold.otf`（600）、`inter_bold.otf`（700）。
- 已裁成只剩 `0-9 , . % + - × −`（与 Web 的 `unicode-range` 一致），保留 `tnum`、`kern`；每个约 7KB（原文件约 620KB）。
  这属于许可允许的修改；字体里的版权和许可说明保留。裁剪命令（fonttools）：

  ```
  pyftsubset Inter-<字重>.otf \
    --unicodes='U+0030-0039,U+002C,U+002E,U+0025,U+002B,U+002D,U+00D7,U+2212' \
    --layout-features='tnum,kern' --name-IDs='*' --notdef-outline \
    --output-file=inter_<字重小写>.otf
  ```
- 用法：`Text.Num.*` 样式（`values/styles.xml`）；数字带单位成对出现时用 `ui/widgets/NumText.kt`。
