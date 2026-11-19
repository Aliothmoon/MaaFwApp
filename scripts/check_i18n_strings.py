#!/usr/bin/env python3
"""
i18n strings.xml 一致性校验与冗余清理工具。

项目的多语言结构(由 translate_maa_strings.py 的工作流决定):
  - res/values/strings.xml      默认资源 = 中文源(source of truth)
  - res/values-en/strings.xml   英文翻译(由 translate_maa_strings.py 生成)
  - res/values-zh/strings.xml   历史遗留的中文冗余副本,应删除
                                (中文 locale "zh" 会自动 fallback 到默认 values)

本脚本做三件事:
  1) 校验(默认):对比默认 values(中文)与 values-en(英文),报告
       - key 不一致:英文漏翻 / 英文多余残留
       - 占位符(%1$s 等)集合不匹配
       - 英文值里残留中文(疑似漏翻,或运行时会 fallback 成中文)
       - 单个文件内重复定义的 key
  2) 扫描 main 源码里注释以外的中文字面量:界面文案走字符串资源,日志与异常消息用英文,
     所以日志调用不跳过;确需保留的逐条登记到 scripts/chinese_literal_allowlist.tsv,
     已不在源码里的登记条目同样报错
  3) 清理(--clean):在确认零丢失后删除冗余的 values-zh。

用法:
  python scripts/check_i18n_strings.py            # 仅校验,有错误时以非零码退出(可作 CI gate)
  python scripts/check_i18n_strings.py --clean    # 先删除冗余 values-zh,再校验
  python scripts/check_i18n_strings.py --no-fail  # 校验但始终以 0 退出(只看报告)
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# ---------- 路径 ----------
PROJECT_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"
VALUES_DEFAULT = RES_DIR / "values" / "strings.xml"   # 中文源(source of truth)
VALUES_EN = RES_DIR / "values-en" / "strings.xml"     # 英文翻译
VALUES_ZH = RES_DIR / "values-zh" / "strings.xml"     # 冗余中文副本
SOURCE_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "java"
ALLOWLIST = PROJECT_ROOT / "scripts" / "chinese_literal_allowlist.tsv"
SOURCE_EXTENSIONS = {".kt", ".java"}
HAN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

# Android 带序号占位符:%1$s、%2$d、%1$04d、%1$dx%2$d 等
PLACEHOLDER_RE = re.compile(r"%\d+\$[-#+ 0-9.]*[a-zA-Z]")
# CJK 统一表意文字 + 常见中日韩标点/全角符号(用于检测英文里残留的中文)
CJK_RE = re.compile(r"[㐀-䶿一-鿿　-〿！-｠]")
# 英文文案里合法含中文的 key:语言自名、专有名词等,不计入“疑似漏翻”
# 引自 MaaMeow 的同名脚本;白名单按本项目实际增补(如语言选择项用语言自身名字显示)
CJK_ALLOWED: set[str] = set()


def parse_strings(path: Path) -> tuple[dict[str, str], list[str]]:
    """解析 strings.xml,返回 ({name: text}, [重复定义的 name])。"""
    root = ET.parse(path).getroot()
    names: list[str] = []
    values: dict[str, str] = {}
    for el in root.findall("string"):
        name = el.get("name")
        if not name:
            continue
        names.append(name)
        # itertext() 兼容内嵌标签;text 为 None 时归一成空串
        values[name] = "".join(el.itertext())
    dups = sorted(n for n, c in Counter(names).items() if c > 1)
    return values, dups


def placeholders(text: str) -> Counter:
    """提取一段文案里的带序号占位符,返回可比较的多重集合。"""
    return Counter(PLACEHOLDER_RE.findall(text))


def clean_redundant_zh() -> bool:
    """删除冗余的 values-zh。删除前确认其所有 key 都在默认 values 中(零丢失)。

    返回 True 表示已删除或本就不存在;False 表示因可能丢失内容而跳过。
    """
    if not VALUES_ZH.exists():
        print("  values-zh 不存在,无需清理。")
        return True

    default_vals, _ = parse_strings(VALUES_DEFAULT)
    zh_vals, _ = parse_strings(VALUES_ZH)

    lost = sorted(k for k in zh_vals if k not in default_vals)
    if lost:
        print("  [跳过删除] values-zh 含默认 values 缺失的 key,删除会丢内容:")
        for k in lost:
            print(f"    - {k}")
        print("  请先把这些 key 同步进 res/values/strings.xml 再清理。")
        return False

    # 同 key 但中文文案不同的,提示但不阻止(默认 values 视为权威来源)
    diff_text = sorted(
        k for k, v in zh_vals.items()
        if k in default_vals and default_vals[k] != v
    )

    VALUES_ZH.unlink()
    print(f"  已删除 {VALUES_ZH.relative_to(PROJECT_ROOT).as_posix()}")

    # 目录若已空,一并移除,使 values-zh 彻底消失
    parent = VALUES_ZH.parent
    if not any(parent.iterdir()):
        parent.rmdir()
        print(f"  已删除空目录 {parent.relative_to(PROJECT_ROOT).as_posix()}/")

    if diff_text:
        print(
            f"  注:有 {len(diff_text)} 条文案与默认 values 不同,已统一以默认 values 为准"
            f"(例:{diff_text[0]})。"
        )
    return True


def check() -> int:
    """校验默认 values(中文)与 values-en(英文),打印报告,返回错误条数。"""
    zh, zh_dups = parse_strings(VALUES_DEFAULT)
    en, en_dups = parse_strings(VALUES_EN)
    errors = 0
    warns = 0

    # 1) 单文件内重复 key
    for label, dups in (("values/strings.xml", zh_dups), ("values-en/strings.xml", en_dups)):
        if dups:
            errors += len(dups)
            print(f"[错误] {label} 重复定义 {len(dups)} 个 key:")
            for k in dups:
                print(f"    - {k}")

    # 2) key 缺失 / 多余
    missing_in_en = sorted(set(zh) - set(en))   # 英文漏翻
    extra_in_en = sorted(set(en) - set(zh))     # 英文残留
    if missing_in_en:
        errors += len(missing_in_en)
        print(f"[错误] 英文漏翻(values-en 缺失,运行时会 fallback 成中文)共 {len(missing_in_en)} 条:")
        for k in missing_in_en:
            print(f"    - {k}")
    if extra_in_en:
        warns += len(extra_in_en)
        print(f"[警告] 英文残留(values-en 多余,中文源已无此 key)共 {len(extra_in_en)} 条:")
        for k in extra_in_en:
            print(f"    - {k}")

    # 3) 占位符不匹配 / 4) 英文里残留中文(仅检查共有 key)
    ph_mismatch: list[str] = []
    cjk_in_en: list[str] = []
    for k in sorted(set(zh) & set(en)):
        if placeholders(zh[k]) != placeholders(en[k]):
            ph_mismatch.append(k)
        if k not in CJK_ALLOWED and CJK_RE.search(en[k]):
            cjk_in_en.append(k)

    if ph_mismatch:
        errors += len(ph_mismatch)
        print(f"[错误] 占位符不匹配(中英文 %n$X 不一致,格式化可能崩溃)共 {len(ph_mismatch)} 条:")
        for k in ph_mismatch:
            print(f"    - {k}")
            print(f"        zh: {zh[k]}")
            print(f"        en: {en[k]}")
    if cjk_in_en:
        warns += len(cjk_in_en)
        print(f"[警告] 英文值里含中文(疑似漏翻)共 {len(cjk_in_en)} 条:")
        for k in cjk_in_en:
            print(f"    - {k}: {en[k]}")

    print()
    print(f"校验完成:中文 {len(zh)} 条,英文 {len(en)} 条;错误 {errors},警告 {warns}。")
    return errors


def _string_end(src: str, start: int) -> int:
    j = start
    n = len(src)
    while j < n:
        c = src[j]
        if c == "\\":
            j += 2
        elif src.startswith("${", j):
            j = _template_end(src, j + 2)
        elif c == '"' or c == "\n":
            return j
        else:
            j += 1
    return n


def _template_end(src: str, start: int) -> int:
    depth = 1
    j = start
    n = len(src)
    while j < n and depth > 0:
        c = src[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        elif c == '"':
            j = _string_end(src, j + 1)
        j += 1
    return j


def _char_end(src: str, i: int) -> int:
    start = i + 3 if src[i + 1:i + 2] == "\\" else i + 2
    close = src.find("'", start)
    return close + 1 if start <= close <= i + 8 else i + 1


def han_literals(src: str) -> list[str]:
    """注释以外、含中文的字符串字面量(引号内的源码原文);模板里嵌套的字符串算在外层那条里"""
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith("//", i):
            end = src.find("\n", i)
            i = n if end < 0 else end
        elif src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
        elif src.startswith('"""', i):
            close = src.find('"""', i + 3)
            close = n if close < 0 else close
            end = min(close + 3, n)
            while end < n and src[end] == '"':
                end += 1
            literal = src[i + 3:max(i + 3, end - 3)]
            if HAN_RE.search(literal):
                out.append(literal)
            i = end
        elif src[i] == '"':
            close = _string_end(src, i + 1)
            literal = src[i + 1:close]
            if HAN_RE.search(literal):
                out.append(literal)
            i = close + 1
        elif src[i] == "'":
            i = _char_end(src, i)
        else:
            i += 1
    return out


def load_allowlist() -> set[tuple[str, str]]:
    entries: set[tuple[str, str]] = set()
    if not ALLOWLIST.exists():
        return entries
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        path, sep, literal = line.partition("\t")
        if not sep:
            raise SystemExit(f"{ALLOWLIST.name} 格式应为 <路径>\\t<字面量>: {line}")
        entries.add((path, literal))
    return entries


def check_source_literals() -> int:
    """扫描 main 源码的中文字面量,返回未登记 + 登记已失效的条数"""
    found: set[tuple[str, str]] = set()
    for file in sorted(SOURCE_ROOT.rglob("*")):
        if file.suffix not in SOURCE_EXTENSIONS or not file.is_file():
            continue
        path = file.relative_to(SOURCE_ROOT).as_posix()
        for literal in han_literals(file.read_text(encoding="utf-8")):
            found.add((path, literal))
    allowed = load_allowlist()
    unexpected = sorted(found - allowed)
    stale = sorted(allowed - found)
    if unexpected:
        print(f"[错误] 源码里未登记的中文字面量共 {len(unexpected)} 条"
              f"(界面文案改用字符串资源,日志与异常消息改英文,确需保留的登记到 {ALLOWLIST.name}):")
        for path, literal in unexpected:
            print(f"    {path}\t{literal}")
    if stale:
        print(f"[错误] {ALLOWLIST.name} 里这些条目已不在源码里,请删除:")
        for path, literal in stale:
            print(f"    {path}\t{literal}")
    print(f"源码扫描完成:中文字面量 {len(found)} 条,已登记 {len(found & allowed)} 条。")
    return len(unexpected) + len(stale)


def main() -> int:
    parser = argparse.ArgumentParser(description="i18n strings.xml 校验与冗余清理")
    parser.add_argument("--clean", action="store_true",
                        help="删除冗余的 values-zh(删除前确认零丢失)")
    parser.add_argument("--no-fail", action="store_true",
                        help="即使有错误也以 0 退出(仅查看报告)")
    args = parser.parse_args()

    if args.clean:
        print("== 清理冗余 values-zh ==")
        clean_redundant_zh()
        print()

    print("== 校验 values(中文) vs values-en(英文) ==")
    errors = check()
    print()
    print("== 扫描 main 源码的中文字面量 ==")
    errors += check_source_literals()

    return 1 if (errors and not args.no_fail) else 0


if __name__ == "__main__":
    sys.exit(main())
