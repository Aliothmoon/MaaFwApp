#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
组装 Python agent 的运行时（解释器 + stdlib + site-packages），铺成 agent.sourceDir 的 bundle 布局

用法:
    python scripts/build_agent_runtime.py --out F:/somewhere/agent-src
    python scripts/build_agent_runtime.py --out ... --require pillow==11.0.0   # 追加依赖
    python scripts/build_agent_runtime.py --out ... --abi arm64-v8a --abi x86_64

产出:
    <out>/<abi>/bundle/{bin/python3, prefix/, site-packages/}
    再配一份 <out>/agent-runtime.json（见 docs/privileged-runtime.md），即可作为 agent.sourceDir

说明:
  - agent 的入口脚本**不由本工具产出**：那是载荷，跟着 PI 走，cwd 就是 PI 根
  - 三方依赖交给 pip 跨平台解析（--only-binary + --platform android_<api>_<abi>），
    pip 只按 wheel 文件名的 tag 过滤、不执行任何构建，所以任意构建机都能解出 Android 轮子
  - Android 的 wheel 目前主要在 Chaquopy 的 pypi-upstream 索引上，PyPI 本体还没有
  - 版本锁 3.13：numpy 的 Android wheel 只有 cp313，而 python.org 只发 3.14/3.15 的 Android 包，
    对得上的解释器在 Maven 上（Chaquopy 发的，cibuildwheel 自己也用这份）
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

PY_VERSION = "3.13.15"
PY_ABI = "cp313"
WHEEL_API = 24  # wheel tag 里的 API 级别，与 NDK 的编译 API 无关
NDK_API = 28

PYTHON_TARBALL = (
    "https://repo1.maven.org/maven2/com/chaquo/python/python/{ver}/python-{ver}-{triple}.tar.gz"
)
EXTRA_INDEX = "https://chaquo.com/pypi-upstream/"

# 下载产物（解释器 tarball、source-only wheel、pip wheel）的本地缓存：跨构建复用，不每次重下
# 默认家目录；用环境变量 MAAFW_AGENT_CACHE 或 --cache 覆盖
DEFAULT_CACHE = Path.home() / ".maafw" / "agent-runtime-cache"

# gradle 用的 ABI 名 -> (NDK triple, wheel tag 里的 ABI 名)
ABIS = {
    "arm64-v8a": ("aarch64-linux-android", "arm64_v8a"),
    "x86_64": ("x86_64-linux-android", "x86_64"),
}

# MaaFramework 的 Python binding 及其依赖；任何 MaaFramework 的 Python agent 都要这几个，
# 不属于某个具体 PI，所以做成默认值而非调用方配置
BASE_REQUIREMENTS = ("numpy==2.3.2", "StrEnum==0.4.15")

# 官方包只发 libpython3.x.so，不发 bin/python3——android.py 的 package() 里写死了白名单把 bin/ 排掉
# 但 Py_BytesMain 是导出的，上游 Modules/python.c 的 main 也就是转调它一句
LAUNCHER_SOURCE = """\
#include <Python.h>

int main(int argc, char **argv)
{
    return Py_BytesMain(argc, argv);
}
"""

# stdlib 里设备上用不到的，占地方且拖慢解包
STDLIB_DROP = ("test", "idlelib", "ensurepip", "turtledemo", "tkinter", "lib2to3", "pydoc_data")

# 三方包里普遍存在的自测与开发工具目录
SITE_DROP_DIRS = ("tests", "test")
SITE_DROP_PACKAGES = {"numpy": ("f2py", "_pyinstaller", "distutils")}


@dataclass(frozen=True)
class Patch:
    """按整段原文替换；对不上就报错退出

    故意不用正则或行号：上游改了这段代码时必须立刻暴露，而不是静默跳过留个运行期 KeyError
    """

    path: str
    old: str
    new: str


@dataclass(frozen=True)
class SourceOnly:
    """只取纯 Python 源码的包

    PyPI 上只有桌面平台的轮子，但代码本身是纯 Python + ctypes，换个 .so 路径就能用；
    pip 的跨平台解析找不到它们（没有 android tag 的轮子），所以单独按名字取
    """

    name: str
    version: str
    platform: str
    keep: tuple[str, ...]
    drop: tuple[str, ...] = ()
    patches: tuple[Patch, ...] = ()


SOURCE_ONLY = (
    # maafw 的 wheel 里打包了整套桌面 native 栈（约 45 MB），Android 上一份都用不了；
    # 只留 maa/ 的 py，.so 换成 MAAFW_BINARY_PATH 指向 nativeLibraryDir 里那份
    SourceOnly(
        "maafw", "5.12.3", "manylinux2014_aarch64",
        keep=("maa",),
        drop=("maa/bin",),
        patches=(
            Patch(
                "maa/library.py",
                "        platform_type = platform.system().lower()\n",
                "        platform_type = platform.system().lower()\n"
                "        # CPython 3.13 起 Android 是独立平台（PEP 738），platform.system() 返回 Android，\n"
                "        # 上游那张表里只有 windows/darwin/linux，直接 KeyError\n"
                "        # 库名与 Linux 完全一致，归一过去即可（Chaquopy 嵌的解释器返回 Linux，走不到这）\n"
                '        if platform_type == "android":\n'
                "            platform_type = LINUX\n",
            ),
        ),
    ),
)


@dataclass
class Paths:
    cache: Path
    bundle: Path
    stdlib: Path = field(init=False)
    site: Path = field(init=False)

    def __post_init__(self) -> None:
        self.stdlib = self.bundle / "prefix" / "lib" / f"python{py_short()}"
        self.site = self.bundle / "site-packages"


def py_short() -> str:
    return ".".join(PY_VERSION.split(".")[:2])


def log(text: str) -> None:
    print(text, flush=True)


def default_cache() -> Path:
    """缓存位置：--cache > MAAFW_AGENT_CACHE > ~/.maafw/agent-runtime-cache""" 
    env = os.environ.get("MAAFW_AGENT_CACHE")
    return Path(env) if env else DEFAULT_CACHE


def fetch(url: str, out: Path, retries: int = 3) -> Path:
    if out.exists() and out.stat().st_size > 0:
        log(f"cached  {out.name}")
        return out
    out.parent.mkdir(parents=True, exist_ok=True)
    part = out.with_name(out.name + ".part")
    last_err: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            log(f"fetch   {url}" + (f"  (retry {attempt}/{retries})" if attempt > 1 else ""))
            with urllib.request.urlopen(url, timeout=60) as response, part.open("wb") as sink:
                shutil.copyfileobj(response, sink)
            if part.stat().st_size == 0:
                raise OSError("空响应")
            part.replace(out)
            return out
        except Exception as e:
            last_err = e
            part.unlink(missing_ok=True)
            if attempt < retries:
                time.sleep(2 ** (attempt - 1))
    raise SystemExit(f"下载失败（{retries} 次重试）: {url}\n  {last_err}")


def rmtree(path: Path) -> None:
    if path.is_dir():
        shutil.rmtree(path, ignore_errors=True)


def extract_tarball(archive: Path, dest: Path) -> None:
    """符号链接在 Windows 上建不出来，落成副本；其余照常"""
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        links = [m for m in members if m.issym() or m.islnk()]
        tar.extractall(dest, members=[m for m in members if m not in links], filter="tar")
        for link in links:
            target = (dest / link.name).parent / link.linkname
            copy_to = dest / link.name
            if target.exists() and not copy_to.exists():
                shutil.copy2(target, copy_to)


def find_clang(ndk: Path, triple: str) -> Path:
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    stem = f"{triple.split('-')[0]}-linux-android{NDK_API}-clang"
    bin_dir = ndk / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    # Windows 上无扩展名的那个是 bash 包装脚本，CreateProcess 起不来，必须挑 .cmd
    for name in (f"{stem}.cmd", f"{stem}.exe", stem):
        if (bin_dir / name).exists():
            return bin_dir / name
    raise SystemExit(f"NDK 里没有 {stem}：{bin_dir}")


def build_launcher(paths: Paths, ndk: Path, triple: str) -> None:
    out = paths.bundle / "bin" / "python3"
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as staging:
        source = Path(staging) / "python_main.c"
        source.write_text(LAUNCHER_SOURCE, encoding="utf-8")
        subprocess.run(
            [
                str(find_clang(ndk, triple)), "-pie", "-O2", "-o", str(out), str(source),
                "-I", str(paths.bundle / "prefix" / "include" / f"python{py_short()}"),
                "-L", str(paths.bundle / "prefix" / "lib"), f"-lpython{py_short()}",
            ],
            check=True,
        )


def install_requirements(paths: Paths, wheel_abi: str, extra: list[str]) -> None:
    """pip 只按 wheel 文件名的 tag 过滤，不执行任何构建，所以能跨平台解析"""
    subprocess.run(
        [
            sys.executable, "-m", "pip", "install", "--quiet",
            "--target", str(paths.site),
            "--cache-dir", str(paths.cache / "pip"),
            "--only-binary", ":all:",
            "--platform", f"android_{WHEEL_API}_{wheel_abi}",
            "--python-version", py_short(),
            "--implementation", "cp",
            "--abi", PY_ABI,
            "--extra-index-url", EXTRA_INDEX,
            *BASE_REQUIREMENTS,
            *extra,
        ],
        check=True,
    )
    # pip 生成的入口脚本在设备上没有用处，还带着构建机的 shebang
    rmtree(site / "bin")


def resolve_pypi_wheel(name: str, version: str, platform: str, cache: Path) -> str:
    cache_file = cache / f"pypi-{name}-{version}.json"
    if cache_file.exists():
        data = json.loads(cache_file.read_text(encoding="utf-8"))
    else:
        log(f"resolve {name} {version} on PyPI")
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(
            f"https://pypi.org/pypi/{name}/{version}/json", timeout=60
        ) as response:
            data = json.load(response)
        cache_file.write_text(json.dumps(data), encoding="utf-8")
    for entry in data["urls"]:
        if entry["filename"].endswith(f"{platform}.whl"):
            return entry["url"]
    raise SystemExit(f"{name} {version} 没有 {platform} 的 wheel")


def install_source_only(paths: Paths) -> None:
    for spec in SOURCE_ONLY:
        url = resolve_pypi_wheel(spec.name, spec.version, spec.platform, paths.cache)
        wheel = fetch(url, paths.cache / url.rsplit("/", 1)[-1])
        staging = paths.cache / f"{spec.name}-extract"
        rmtree(staging)
        with zipfile.ZipFile(wheel) as archive:
            archive.extractall(staging)
        for name in spec.keep:
            rmtree(paths.site / name)
            shutil.copytree(staging / name, paths.site / name)
        for name in spec.drop:
            rmtree(paths.site / name)
        for patch in spec.patches:
            target = paths.site / patch.path
            source = target.read_text(encoding="utf-8")
            if patch.old not in source:
                raise SystemExit(
                    f"{spec.name} {spec.version} 的 {patch.path} 对不上补丁，上游多半改了这段：\n{patch.old}"
                )
            target.write_text(source.replace(patch.old, patch.new, 1), encoding="utf-8")
            log(f"  patched {patch.path}")


def trim(paths: Paths) -> None:
    for name in STDLIB_DROP:
        rmtree(paths.stdlib / name)
    for path in paths.stdlib.glob("config-*"):
        rmtree(path)
    for package, names in SITE_DROP_PACKAGES.items():
        for name in names:
            rmtree(paths.site / package / name)
    for root in (paths.stdlib, paths.site):
        for cache in list(root.rglob("__pycache__")):
            rmtree(cache)
        for name in SITE_DROP_DIRS:
            for directory in list(root.rglob(name)):
                if directory.is_dir():
                    rmtree(directory)


def zip_pure_python(source: Path, archive: Path, skip_dirs: tuple[str, ...] = ()) -> int:
    """把纯 py 收进 zip 交给 zipimport，不落地

    解包慢主要慢在文件数——stdlib 一处就是五百多个。扩展模块必须留在磁盘上：
    dlopen 要真实路径，zip 里的读不出来
    不预编译 pyc：魔数与解释器版本绑定，构建机未必装着同版本的 CPython
    """
    def skipped(path: Path) -> bool:
        rel = path.relative_to(source)
        return bool(rel.parts) and rel.parts[0] in skip_dirs

    count = 0
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as sink:
        for path in sorted(source.rglob("*.py")):
            if skipped(path):
                continue
            sink.write(path, path.relative_to(source).as_posix())
            count += 1
    for path in list(source.rglob("*.py")):
        if not skipped(path):
            path.unlink()
    return count


def pack_site_packages(site: Path) -> tuple[int, list[str]]:
    """纯 Python 的包收进 pure.zip，带 .so 的留在磁盘上

    这是 Chaquopy 那套分类的等价物，区别是它靠自造 loader 把同一个包的 py 与 so
    合并进一个 __path__，我们只用标准 zipimport——所以混装的包只能整个留在磁盘上
    """
    archive = site / "pure.zip"
    kept: list[str] = []
    zipped: list[Path] = []
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as sink:
        for child in sorted(site.iterdir()):
            if child.name == archive.name:
                continue
            if child.is_dir() and any(child.rglob("*.so")):
                kept.append(child.name)
                continue
            if child.is_dir():
                for path in sorted(child.rglob("*")):
                    if path.is_file():
                        sink.write(path, path.relative_to(site).as_posix())
                zipped.append(child)
            elif child.suffix == ".py":
                sink.write(child, child.name)
                zipped.append(child)
    for path in zipped:
        rmtree(path) if path.is_dir() else path.unlink()
    return len(zipped), kept


def build_abi(cache: Path, out: Path, abi: str, ndk: Path, extra: list[str]) -> None:
    triple, wheel_abi = ABIS[abi]
    paths = Paths(cache, out / abi / "bundle")
    log(f"── {abi} ──")

    rmtree(paths.bundle)
    paths.bundle.mkdir(parents=True)
    tarball = fetch(
        PYTHON_TARBALL.format(ver=PY_VERSION, triple=triple),
        cache / f"python-{PY_VERSION}-{triple}.tar.gz",
    )
    extract_tarball(tarball, paths.bundle)
    # 包里除 prefix/ 之外都是给构建用的，别一起塞进设备
    for name in ("testbed", "android.py", "android-env.sh", "README.md"):
        target = paths.bundle / name
        rmtree(target) if target.is_dir() else target.unlink(missing_ok=True)

    build_launcher(paths, ndk, triple)
    # 头文件只在上一步编 launcher 时要用，设备上纯占地方
    rmtree(paths.bundle / "prefix" / "include")
    rmtree(paths.bundle / "prefix" / "lib" / "pkgconfig")

    paths.site.mkdir(parents=True, exist_ok=True)
    install_requirements(paths, wheel_abi, extra)
    install_source_only(paths)
    trim(paths)

    stdlib_count = zip_pure_python(
        paths.stdlib,
        paths.stdlib.parent / f"python{py_short().replace('.', '')}.zip",
        skip_dirs=("lib-dynload",),
    )
    site_count, native = pack_site_packages(paths.site)

    files = sum(1 for path in paths.bundle.rglob("*") if path.is_file())
    size = sum(path.stat().st_size for path in paths.bundle.rglob("*") if path.is_file())
    log(f"  stdlib 收进 zip：{stdlib_count} 个 py")
    log(f"  site-packages 收进 zip：{site_count} 项；留在磁盘（含 .so）：{', '.join(native) or '无'}")
    log(f"  {files} 个文件 / {size / 1048576:.1f} MB -> {paths.bundle}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--out", required=True, help="产出根目录，即 agent.sourceDir")
    parser.add_argument("--abi", action="append", choices=sorted(ABIS), help="默认只出 arm64-v8a")
    parser.add_argument("--require", action="append", default=[], help="追加三方依赖，可重复")
    parser.add_argument("--ndk", default=os.environ.get("NDK"), help="NDK 根目录，或用环境变量 NDK")
    parser.add_argument("--cache", help=f"下载缓存目录，默认 {DEFAULT_CACHE}（或环境变量 MAAFW_AGENT_CACHE）")
    args = parser.parse_args()

    if not args.ndk:
        raise SystemExit("需要 --ndk 或环境变量 NDK")
    ndk = Path(args.ndk)
    if not ndk.is_dir():
        raise SystemExit(f"NDK 不存在：{ndk}")

    out = Path(args.out).resolve()
    cache = Path(args.cache) if args.cache else default_cache()
    cache.mkdir(parents=True, exist_ok=True)

    for abi in args.abi or ["arm64-v8a"]:
        build_abi(cache, out, abi, ndk, args.require)

    log(f"agent.sourceDir = {out}")
    log("别忘了在同目录放一份 agent-runtime.json（见 docs/privileged-runtime.md）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
