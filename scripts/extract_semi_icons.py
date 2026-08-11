#!/usr/bin/env python3
"""从 @douyinfe/semi-icons / semi-icons-lab 全量提取 Android Vector Drawable。

推荐输入目录（任选其一，或两者都有）：
  tmp/package/src/svgs          # 主包 monochrome
  tmp/package_lab/src/svgs      # lab 包：含 monochrome + 彩色 lab

用法：
  npm pack @douyinfe/semi-icons @douyinfe/semi-icons-lab
  tar xf … && 放到 tmp/package 与 tmp/package_lab
  python scripts/extract_semi_icons.py

产出（:semi-icons 模块）：
  semi-icons/src/main/res/drawable/ic_semi_*.xml
  semi-icons/src/main/res/drawable/ic_semi_lab_*.xml
  semi-icons/.../semiicons/SemiIconRes.kt
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "semi-icons"
OUT = MODULE / "src" / "main" / "res" / "drawable"
KT_OUT = (
    MODULE
    / "src"
    / "main"
    / "java"
    / "com"
    / "aliothmoon"
    / "maafw"
    / "semiicons"
    / "SemiIconRes.kt"
)

SVG_DIRS = [
    ROOT / "tmp" / "package_lab" / "src" / "svgs",
    ROOT / "tmp" / "package" / "src" / "svgs",
]


def local(tag: str) -> str:
    return tag.split("}")[-1]


def res_name(stem: str, prefix: str) -> str:
    # Android 资源名：小写、下划线、不能数字开头
    s = stem.replace("-", "_").replace(" ", "_")
    s = re.sub(r"[^a-zA-Z0-9_]", "_", s).lower()
    s = re.sub(r"_+", "_", s).strip("_")
    if s and s[0].isdigit():
        s = f"n_{s}"
    return f"{prefix}{s}"


def parse_color(fill: str | None) -> str | None:
    if not fill or fill == "none":
        return None
    if fill == "black":
        return "#FF000000"
    if fill == "white":
        return "#FFFFFFFF"
    m = re.fullmatch(r"#([0-9A-Fa-f]{3,8})", fill)
    if not m:
        return None
    h = m.group(1)
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
        return f"#FF{h.upper()}"
    if len(h) == 6:
        return f"#FF{h.upper()}"
    if len(h) == 8:
        # SVG #RRGGBBAA vs Android #AARRGGBB — Semi 多用 RRGGBB
        return f"#{h.upper()}"
    return None


def collect_shapes(root: ET.Element) -> list[tuple[str, str, str | None, str]]:
    """返回 (kind, data, fill_rule, fillColor)；kind=path|circle|rect"""
    shapes: list[tuple[str, str, str | None, str]] = []
    for el in root.iter():
        tag = local(el.tag)
        raw_fill = el.attrib.get("fill")
        if raw_fill == "none":
            continue
        fill = parse_color(raw_fill)
        if fill is None:
            # 未写 fill 时 SVG 默认 black；无法解析的跳过
            if raw_fill is None:
                fill = "#FF000000"
            else:
                continue
        if tag == "path":
            d = el.attrib.get("d")
            if not d:
                continue
            fr = el.attrib.get("fill-rule") or el.attrib.get("fillRule")
            shapes.append(("path", d, fr, fill))
        elif tag == "circle":
            cx = el.attrib.get("cx", "0")
            cy = el.attrib.get("cy", "0")
            r = el.attrib.get("r", "0")
            shapes.append(("circle", f"{cx},{cy},{r}", None, fill))
        elif tag == "rect":
            x = el.attrib.get("x", "0")
            y = el.attrib.get("y", "0")
            w = el.attrib.get("width", "0")
            h = el.attrib.get("height", "0")
            shapes.append(("rect", f"{x},{y},{w},{h}", None, fill))
    return shapes


def is_multicolor(shapes: list[tuple[str, str, str | None, str]]) -> bool:
    colors = {s[3] for s in shapes}
    return len(colors) > 1 or any(c not in ("#FF000000", "#FFFFFFFF") for c in colors)


def circle_to_path(cx: float, cy: float, r: float) -> str:
    # 近似圆 path（Android path 支持 arc）
    return (
        f"M{cx - r},{cy}"
        f"a{r},{r} 0 1,0 {r * 2},0"
        f"a{r},{r} 0 1,0 {-r * 2},0"
    )


def rect_to_path(x: float, y: float, w: float, h: float) -> str:
    return f"M{x},{y}h{w}v{h}h{-w}z"


def write_vector(
    out_path: Path,
    source_name: str,
    vw: float,
    vh: float,
    shapes: list[tuple[str, str, str | None, str]],
    *,
    tintable: bool,
) -> None:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Semi Design icon; source @douyinfe/semi-icons / semi-icons-lab (MIT) -->",
        f"<!-- svg: {source_name} -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        f'    android:viewportWidth="{vw:g}"',
        f'    android:viewportHeight="{vh:g}"',
    ]
    if tintable:
        lines[-1] = lines[-1]  # keep
        lines.append('    android:tint="?attr/colorControlNormal">')
    else:
        lines.append("    >")

    for kind, data, fr, fill in shapes:
        if kind == "path":
            d = data
        elif kind == "circle":
            cx, cy, r = map(float, data.split(","))
            d = circle_to_path(cx, cy, r)
        elif kind == "rect":
            x, y, w, h = map(float, data.split(","))
            d = rect_to_path(x, y, w, h)
        else:
            continue
        # tintable 单色：白 + tint；彩色：保留原色
        color = "@android:color/white" if tintable else fill
        ft = ' android:fillType="evenOdd"' if (fr or "").lower() == "evenodd" else ""
        lines.append(f"    <path{ft}")
        lines.append(f'        android:fillColor="{color}"')
        lines.append(f'        android:pathData="{d}"/>')
    lines.append("</vector>")
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def convert_svg(svg_path: Path) -> tuple[str, str] | None:
    """返回 (resource_name_without_ext, category mono|lab)"""
    text = svg_path.read_text(encoding="utf-8")
    # 跳过依赖 mask 的复杂稿（Android Vector 难还原）
    if re.search(r"<mask[\s>]", text):
        print("SKIP_MASK", svg_path.name)
        return None

    text2 = re.sub(r'xmlns(?::\w+)?="[^"]*"', "", text)
    try:
        root = ET.fromstring(text2)
    except ET.ParseError as e:
        print("PARSE", svg_path.name, e)
        return None

    vb = root.attrib.get("viewBox", "0 0 24 24").split()
    if len(vb) != 4:
        print("VB", svg_path.name)
        return None
    vw, vh = float(vb[2]), float(vb[3])
    shapes = collect_shapes(root)
    if not shapes:
        print("NOPATH", svg_path.name)
        return None

    multi = is_multicolor(shapes)
    prefix = "ic_semi_lab_" if multi else "ic_semi_"
    name = res_name(svg_path.stem, prefix)
    out_path = OUT / f"{name}.xml"
    write_vector(
        out_path,
        svg_path.name,
        vw,
        vh,
        shapes,
        tintable=not multi,
    )
    return name, ("lab" if multi else "mono")


def gather_svgs() -> dict[str, Path]:
    """stem -> path；后出现的目录覆盖先出现的（package_lab 优先）"""
    found: dict[str, Path] = {}
    for d in reversed(SVG_DIRS):
        if not d.is_dir():
            continue
        for p in d.glob("*.svg"):
            found[p.stem] = p
    # package_lab 优先：再扫一遍 lab
    lab = ROOT / "tmp" / "package_lab" / "src" / "svgs"
    if lab.is_dir():
        for p in lab.glob("*.svg"):
            found[p.stem] = p
    return found


def kotlin_prop(name: str) -> str:
    # ic_semi_home -> home；ic_semi_lab_button -> labButton / lab_button
    raw = name
    if raw.startswith("ic_semi_lab_"):
        body = raw[len("ic_semi_lab_") :]
        prop = "lab_" + body
    elif raw.startswith("ic_semi_"):
        prop = raw[len("ic_semi_") :]
    else:
        prop = raw
    # Kotlin 标识符
    if prop[0].isdigit():
        prop = f"n{prop}"
    # 关键字
    if prop in {"object", "class", "fun", "val", "var", "when", "in", "is"}:
        prop = f"{prop}_"
    return prop


def write_kotlin(entries: list[tuple[str, str]]) -> None:
    """entries: (res_name, category)"""
    mono = sorted(n for n, c in entries if c == "mono")
    lab = sorted(n for n, c in entries if c == "lab")
    lines = [
        "package com.aliothmoon.maafw.semiicons",
        "",
        "import androidx.annotation.DrawableRes",
        "import com.aliothmoon.maafw.semiicons.R",
        "",
        "/**",
        " * Semi 图标资源索引（由 scripts/extract_semi_icons.py 生成，勿手改）",
        " * 模块：:semi-icons",
        " *",
        " * - [Mono]：`@douyinfe/semi-icons` 单色，可 tint，走 `Icon(..., tint=…)`",
        " * - [Lab]：`@douyinfe/semi-icons-lab` 彩色，不可 tint",
        " *",
        f" * Mono {len(mono)} · Lab {len(lab)}",
        " */",
        "object SemiIconRes {",
        "",
        "    /** 单色图标（可 tint） */",
        "    object Mono {",
    ]
    for n in mono:
        prop = kotlin_prop(n)
        lines.append(f"        @DrawableRes val {prop}: Int = R.drawable.{n}")
    lines += [
        "    }",
        "",
        "    /** 彩色 lab 图标（保留原色，不要再 tint） */",
        "    object Lab {",
    ]
    for n in lab:
        prop = kotlin_prop(n)
        lines.append(f"        @DrawableRes val {prop}: Int = R.drawable.{n}")
    lines += [
        "    }",
        "}",
        "",
    ]
    KT_OUT.write_text("\n".join(lines), encoding="utf-8")
    print("WROTE", KT_OUT.relative_to(ROOT), "mono", len(mono), "lab", len(lab))


def main() -> None:
    svgs = gather_svgs()
    if not svgs:
        raise SystemExit(
            "no svgs; unpack @douyinfe/semi-icons and/or semi-icons-lab into tmp/"
        )
    OUT.mkdir(parents=True, exist_ok=True)
    # 清掉旧的 ic_semi_* 以免残留
    for old in OUT.glob("ic_semi_*.xml"):
        old.unlink()

    entries: list[tuple[str, str]] = []
    ok = skip = 0
    for stem in sorted(svgs.keys()):
        result = convert_svg(svgs[stem])
        if result is None:
            skip += 1
            continue
        name, cat = result
        entries.append((name, cat))
        ok += 1
        if ok % 50 == 0:
            print(f"... {ok}")

    write_kotlin(entries)
    print(f"done ok={ok} skip={skip} total_svg={len(svgs)}")


if __name__ == "__main__":
    main()
