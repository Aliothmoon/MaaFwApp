#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 GitHub Release 下载 MaaFramework 的 Android 产物，把 .so 铺进 app/src/main/jniLibs/<abi>/

用法:
    python scripts/setup_maa_framework.py                  # 取 latest release
    python scripts/setup_maa_framework.py --tag v4.5.0     # 取指定 tag
    python scripts/setup_maa_framework.py --skip-download  # 只用缓存重新铺一遍
    python scripts/setup_maa_framework.py --abi arm64-v8a  # 只处理一个 ABI

说明:
  - release 产物是 zip（`MAA-android-<arch>-<tag>.zip`），.so 在压缩包的 bin/ 下
  - libc++_shared.so 保留上游那份：MaaFramework 各 so 都链接它
  - bin/plugins/ 是 MaaPluginDemo 的示例插件，默认不打包，要的话加 --with-plugins
  - 目标目录每次铺之前先清空，避免残留上个版本的 .so
"""

import argparse
import io
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

# Windows 控制台编码
if sys.platform == "win32":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

DEFAULT_GITHUB_REPO = "MaaXYZ/MaaFramework"
API_BASE = f"https://api.github.com/repos/{DEFAULT_GITHUB_REPO}"

# release 产物名里的 arch 关键字 -> jniLibs 子目录
ABI_MAP = {
    "android-aarch64": "arm64-v8a",
    "android-x86_64": "x86_64",
}

# 默认不排除任何 so
# 特别注意 libc++_shared.so：MaaFramework 的各个 so 都动态链接它，且是上游那套 NDK 编的；
# 换成本地 NDK 的那份等于同进程混两套 libc++。bridge 侧改用 c++_static，不参与竞争
EXCLUDE_SO: set[str] = set()

# 示例插件，默认不打包
PLUGIN_DIR_PART = "plugins"

JNILIBS_DIR = "app/src/main/jniLibs"
CACHE_DIR = ".maa-cache"
VERSION_FILE = ".maafwversion"

# 缺了这些库 native 侧起不来，铺完校验一遍
REQUIRED_SO = {
    "libMaaFramework.so",
    "libMaaUtils.so",
    "libMaaAndroidNativeControlUnit.so",
}

# MAA-android-aarch64-v4.5.0.zip / MAA-android-x86_64-v4.5.0-beta.1.zip
ZIP_VERSION_RE = re.compile(r"-android-(?:aarch64|x86_64)-(v[0-9A-Za-z.\-+]+)\.zip$")


def get_project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _request(url: str, accept: str, with_auth: bool, token: str | None):
    req = urllib.request.Request(url)
    req.add_header("Accept", accept)
    req.add_header("User-Agent", "MaaFwApp-Setup")
    if with_auth and token:
        req.add_header("Authorization", f"token {token}")
    return req


def fetch_json(url: str) -> dict:
    token = os.environ.get("GITHUB_TOKEN")

    def _do(with_auth: bool) -> dict:
        with urllib.request.urlopen(
            _request(url, "application/vnd.github.v3+json", with_auth, token), timeout=30
        ) as resp:
            return json.loads(resp.read().decode("utf-8"))

    if not token:
        return _do(with_auth=False)
    try:
        return _do(with_auth=True)
    except urllib.error.HTTPError as e:
        # GITHUB_TOKEN 作用域限于当前仓库，访问别的公开仓库时会 401，退回匿名
        if e.code == 401:
            print(f"[WARN] 带 token 请求被拒（{e.code}），改用匿名重试")
            return _do(with_auth=False)
        raise


def download_file(url: str, dest: Path):
    print(f"  [DOWNLOAD] {dest.name}")
    token = os.environ.get("GITHUB_TOKEN")
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        resp_ctx = urllib.request.urlopen(
            _request(url, "application/octet-stream", bool(token), token), timeout=600
        )
    except urllib.error.HTTPError as e:
        if e.code == 401 and token:
            print(f"[WARN] 带 token 下载被拒（{e.code}），改用匿名重试")
            resp_ctx = urllib.request.urlopen(
                _request(url, "application/octet-stream", False, token), timeout=600
            )
        else:
            raise
    with resp_ctx as resp:
        total = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(1024 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total > 0:
                    pct = downloaded * 100 // total
                    print(
                        f"\r    {downloaded / (1024 * 1024):.1f}/{total / (1024 * 1024):.1f} MB ({pct}%)",
                        end="",
                        flush=True,
                    )
        print()


def get_release_assets(tag: str | None) -> tuple[str, list]:
    url = f"{API_BASE}/releases/tags/{tag}" if tag else f"{API_BASE}/releases/latest"
    print(f"[FETCH] {url}")
    try:
        data = fetch_json(url)
    except urllib.error.HTTPError as e:
        print(f"[ERROR] 请求失败: {e.code} {e.reason}")
        sys.exit(1)
    tag_name = data.get("tag_name", "unknown")
    print(f"  tag: {tag_name}")
    return tag_name, data.get("assets", [])


def find_android_assets(assets: list) -> dict:
    result = {}
    for asset in assets:
        name = asset["name"]
        if not name.endswith(".zip"):
            continue
        for keyword, abi in ABI_MAP.items():
            if keyword in name:
                result[abi] = {
                    "name": name,
                    "url": asset["browser_download_url"],
                    "size": asset["size"],
                }
    return result


def deploy_zip(archive: Path, abi: str, project_root: Path, with_plugins: bool) -> dict:
    jnilib_dir = project_root / JNILIBS_DIR / abi
    if jnilib_dir.exists():
        shutil.rmtree(jnilib_dir)
    jnilib_dir.mkdir(parents=True, exist_ok=True)

    stats = {"so": 0, "skipped": 0, "plugins": 0}
    print(f"  [EXTRACT] {archive.name} -> {abi}")
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            parts = Path(info.filename).parts
            name = parts[-1]
            if not name.endswith(".so"):
                continue
            if name in EXCLUDE_SO:
                stats["skipped"] += 1
                continue
            if PLUGIN_DIR_PART in parts[:-1]:
                if not with_plugins:
                    stats["plugins"] += 1
                    continue
            # jniLibs/<abi>/ 是平铺的，同名会互相覆盖
            dest = jnilib_dir / name
            if dest.exists():
                print(f"    [WARN] 同名 .so 冲突，后者覆盖前者: {info.filename}")
            with zf.open(info) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
            stats["so"] += 1

    missing = REQUIRED_SO - {f.name for f in jnilib_dir.iterdir()}
    if missing:
        print(f"    [WARN] 缺少关键库: {', '.join(sorted(missing))}")

    plugin_note = f", plugins 跳过: {stats['plugins']}" if stats["plugins"] else ""
    print(f"    so: {stats['so']}, 排除: {stats['skipped']}{plugin_note}")
    return stats


def main():
    parser = argparse.ArgumentParser(description="下载并铺开 MaaFramework 的 Android .so")
    parser.add_argument("--repo", "-r", default=DEFAULT_GITHUB_REPO,
                        help=f"GitHub 仓库（owner/repo，默认 {DEFAULT_GITHUB_REPO}）")
    parser.add_argument("--tag", "-t", help="指定 release tag，默认取 latest")
    parser.add_argument("--skip-download", "-s", action="store_true", help="跳过下载，只用缓存")
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64", "all"], default="all",
                        help="只处理指定 ABI，默认全部")
    parser.add_argument("--with-plugins", action="store_true",
                        help="连 bin/plugins/ 下的示例插件一起打包")
    args = parser.parse_args()

    global API_BASE
    API_BASE = f"https://api.github.com/repos/{args.repo}"

    project_root = get_project_root()
    cache_dir = project_root / CACHE_DIR
    target_abis = list(ABI_MAP.values()) if args.abi == "all" else [args.abi]

    print("=" * 55)
    print("==> MaaFramework Android 产物部署")
    print("=" * 55)

    if not args.skip_download:
        _, assets = get_release_assets(args.tag)
        android_assets = find_android_assets(assets)
        if not android_assets:
            print("[ERROR] release 里没有 Android 产物，确认该 tag 是否包含 MAA-android-*.zip")
            sys.exit(1)

        print(f"\n[INFO] 找到 {len(android_assets)} 个 Android 产物:")
        for abi, info in android_assets.items():
            print(f"  {abi}: {info['name']} ({info['size'] / (1024 * 1024):.1f} MB)")

        print(f"\n[DOWNLOAD] 缓存到 {cache_dir}")
        for abi, info in android_assets.items():
            if abi not in target_abis:
                continue
            dest = cache_dir / info["name"]
            if dest.exists() and dest.stat().st_size == info["size"]:
                print(f"  [CACHE] {info['name']} 已存在，跳过")
            else:
                download_file(info["url"], dest)
    else:
        print("[SKIP] 跳过下载，使用缓存")

    print("\n[DEPLOY] 铺开产物")
    deployed = False
    deployed_version = None
    for archive in sorted(cache_dir.glob("MAA-android-*.zip")):
        for keyword, abi in ABI_MAP.items():
            if keyword in archive.name and abi in target_abis:
                deploy_zip(archive, abi, project_root, args.with_plugins)
                deployed = True
                m = ZIP_VERSION_RE.search(archive.name)
                if m:
                    deployed_version = m.group(1)

    if not deployed:
        print("[ERROR] 缓存里没有 MAA-android-*.zip，先不带 --skip-download 跑一次")
        sys.exit(1)

    if deployed_version:
        (project_root / VERSION_FILE).write_text(deployed_version + "\n", encoding="utf-8")
        print(f"  [VERSION] {VERSION_FILE}: {deployed_version}")
    else:
        print(f"[WARN] 从产物名解析不出版本，{VERSION_FILE} 未更新")

    print("\n" + "=" * 55)
    print("[DONE] 部署完成")
    print("=" * 55)
    for abi in target_abis:
        jnilib_dir = project_root / JNILIBS_DIR / abi
        if not jnilib_dir.exists():
            continue
        so_files = sorted(f for f in jnilib_dir.iterdir() if f.suffix == ".so")
        total = sum(f.stat().st_size for f in so_files)
        print(f"  {abi}/: {len(so_files)} 个 so, {total / (1024 * 1024):.1f} MB")
        for f in so_files:
            print(f"    {f.name}  {f.stat().st_size / (1024 * 1024):.1f} MB")


if __name__ == "__main__":
    main()
