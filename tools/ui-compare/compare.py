#!/usr/bin/env python3
"""原型 vs APP 并排对照（F4-1）。

按文件名（<NN-name>.png）配对原型截图与 APP 渲染，生成：
  tools/ui-compare/out/compare/<NN-name>.png   左原型、右 APP，带标题
  tools/ui-compare/out/compare/contact-sheet.png  全部状态拼成一张总览

用法：python3 tools/ui-compare/compare.py [--config 393-1.0]
APP 渲染来自 android/app/build/screens/<宽度>-<字号>/；原型截图来自 tools/ui-compare/out/proto/。
"""
import argparse
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
CJK_FONTS = [
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "C:/Windows/Fonts/msyh.ttc",
]


def load_font(size):
    for p in CJK_FONTS:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size), True
            except OSError:
                pass
    return ImageFont.load_default(), False  # 没有中文字体：标题退回文件名（ASCII）


def fit_height(im, h):
    return im.resize((max(1, round(im.width * h / im.height)), h), Image.LANCZOS)


def label(text, ascii_text, has_cjk):
    return text if has_cjk else ascii_text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="393-1.0", help="用哪一套 APP 渲染，如 393-1.0、360-1.3")
    ap.add_argument("--proto", default=os.path.join(HERE, "out", "proto"))
    ap.add_argument("--app", default=None)
    ap.add_argument("--out", default=os.path.join(HERE, "out", "compare"))
    ap.add_argument("--height", type=int, default=1100, help="每张图的统一高度（像素）")
    args = ap.parse_args()

    app_dir = args.app or os.path.join(REPO, "android", "app", "build", "screens", args.config)
    protos = {os.path.basename(f): f for f in glob.glob(os.path.join(args.proto, "*.png"))}
    apps = {os.path.basename(f): f for f in glob.glob(os.path.join(app_dir, "*.png"))}
    names = sorted(set(protos) | set(apps))
    if not names:
        sys.exit(f"没有图：原型 {args.proto}，APP {app_dir}。先跑 proto-shots.mjs 和 ./gradlew :app:screens")

    os.makedirs(args.out, exist_ok=True)
    for f in glob.glob(os.path.join(args.out, "*.png")):
        os.remove(f)

    font, cjk = load_font(30)
    small, _ = load_font(24)
    pad, bar = 24, 56
    h = args.height
    pairs = []
    missing = []
    for n in names:
        stem = n[:-4]
        cells = []
        for title, ascii_title, src in (("原型", "proto", protos.get(n)), (f"APP {args.config}", f"APP {args.config}", apps.get(n))):
            if src:
                cells.append((label(title, ascii_title, cjk), fit_height(Image.open(src).convert("RGB"), h)))
            else:
                missing.append(f"{stem}：缺{'原型' if title == '原型' else 'APP'}图")
                ph = Image.new("RGB", (round(h * 0.49), h), (238, 238, 238))
                d = ImageDraw.Draw(ph)
                d.text((20, h // 2), label("没有这张图", "missing", cjk), fill=(120, 120, 120), font=font)
                cells.append((label(title, ascii_title, cjk), ph))
        width = sum(c[1].width for c in cells) + pad * 3
        im = Image.new("RGB", (width, h + bar + pad), (250, 250, 248))
        d = ImageDraw.Draw(im)
        x = pad
        for title, c in cells:
            d.text((x, 12), f"{stem}  {title}", fill=(30, 30, 30), font=font)
            im.paste(c, (x, bar))
            x += c.width + pad
        im.save(os.path.join(args.out, n))
        pairs.append(im)

    # 总览：每行放 3 个状态
    per_row = 3
    thumb_w = 760
    thumbs = [p.resize((thumb_w, round(p.height * thumb_w / p.width)), Image.LANCZOS) for p in pairs]
    rows = [thumbs[i:i + per_row] for i in range(0, len(thumbs), per_row)]
    row_h = [max(t.height for t in r) for r in rows]
    head = 70
    sheet = Image.new("RGB", (per_row * thumb_w + pad * (per_row + 1), head + sum(row_h) + pad * (len(rows) + 1)), (255, 255, 255))
    d = ImageDraw.Draw(sheet)
    d.text((pad, 18), label(f"原型（左）与 APP {args.config}（右）对照", f"proto (left) vs APP {args.config} (right)", cjk), fill=(20, 20, 20), font=font)
    y = head
    for r, rh in zip(rows, row_h):
        x = pad
        for t in r:
            sheet.paste(t, (x, y))
            x += thumb_w + pad
        y += rh + pad
    sheet.save(os.path.join(args.out, "contact-sheet.png"))
    print(f"{len(pairs)} 组对照 -> {args.out}")
    print("总览：", os.path.join(args.out, "contact-sheet.png"))
    for m in missing:
        print("缺图：", m)


if __name__ == "__main__":
    main()
