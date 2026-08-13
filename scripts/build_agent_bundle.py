#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build a Python agent runtime for a MaaFramework PI project.

The prebuilt core (CPython + stdlib + the maa package) is downloaded from
MaaAgentCoreAndroid releases into .maafw/; this script only adds the project's
own dependencies on top and packs the result. No NDK required.

Usage:
    python scripts/build_agent_bundle.py --out F:/Build/m9a-agent \
        --requirements F:/Project/Python/M9A/requirements.txt --no-deps

    # Pillow and friends live on Chaquopy's own index, at older versions
    ... --exclude pillow --require pillow==11.0.0 \
        --extra-index-url https://chaquo.com/pypi-13.1/

Output:
    <out>/<abi>/bundle/{bin/python3, prefix/, site-packages/}
    which is exactly the agent.sourceDir a packaging profile expects.

requirements.txt cannot be handed to pip as-is. Three filters apply, each logged:
  1. Environment markers are evaluated here. pip's --platform only picks wheel
     tags; markers are still evaluated against the build machine, so on Windows
     `colorama ; sys_platform == 'win32'` would be installed into an Android bundle.
  2. Anything the core already ships (maafw / numpy / StrEnum) is dropped, even
     when the project pins a different version -- the Android index usually has
     exactly one version and the project's pin would not resolve at all.
  3. DEVICE_DROP entries are dropped: desktop artifacts with no meaning on Android.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

CORE_REPO = "Aliothmoon/MaaAgentCoreAndroid"
CORE_TAG = "3.13.15-maafw5.12.3"
CORE_PY = "3.13.15"
CORE_URL = "https://github.com/{repo}/releases/download/{tag}/{asset}"
CORE_MANIFEST = "agent-core.json"

# 下载与解包都落在项目里，跨构建复用
WORK_DIR = Path(__file__).resolve().parents[1] / ".maafw"

EXTRA_INDEX = "https://chaquo.com/pypi-upstream/"

# marker 里的 platform_machine，按 ABI 分
MACHINES = {"arm64-v8a": "aarch64", "x86_64": "x86_64"}

# 桌面端的 agent 可执行体；设备上走 MAAFW_BINARY_PATH 指向 nativeLibraryDir
DEVICE_DROP = {"maaagentbinary"}

# Chaquopy 的 native 包把 .so 收在这儿，而依赖它们的扩展（PIL 的 _imaging 等）
# 没有 RUNPATH，只能靠 LD_LIBRARY_PATH 找
CHAQUOPY_LIB = "chaquopy/lib"

SITE_DROP_DIRS = ("tests", "test")
SITE_DROP_PACKAGES = {
    "numpy": ("f2py", "_pyinstaller", "distutils"),
    # Chaquopy 的 native 包连头文件一起发，设备上只要 lib/ 里那几个 .so
    "chaquopy": ("include", "lib/pkgconfig"),
}

# 包名结束的位置：版本号、extras、marker、直接引用都从这些字符开始
NAME_END = re.compile(r"[\s\[=<>!~;(@]")

MULTIPROCESSING_SHIM = '''\
"""
Android 上 CPython 不编译 `_multiprocessing`：bionic 没有 POSIX 命名信号量（sem_open），
而该扩展的 SemLock 正建立在它之上

这份补齐只服务「单进程内用到 multiprocessing API」的场景——Android 上本来也 fork 不出
multiprocessing.Process，真正的跨进程语义无从谈起。典型用户是 loguru 的 `enqueue=True`：
它要一个 SimpleQueue，而 SimpleQueue 只要一把锁

`multiprocessing/synchronize.py` 只引用 SemLock 一个符号，`queues.py` 不直接引用，
`connection.py` 里那几个（recv/send/closesocket）是 Windows 分支，POSIX 走不到
"""

import threading
import time

SEM_VALUE_MAX = 2**31 - 1

# 顺序照抄 multiprocessing/synchronize.py 的 `RECURSIVE_MUTEX, SEMAPHORE = list(range(2))`
# 写反了 RLock 会退化成不可重入锁，第二次 acquire 直接死等
_RECURSIVE_MUTEX = 0
_SEMAPHORE = 1


class SemLock:
    """threading 模拟的信号量；接口对齐 CPython 的 C 实现，只覆盖 POSIX 路径用得到的部分"""

    SEM_VALUE_MAX = SEM_VALUE_MAX

    def __init__(self, kind, value, maxvalue, name, unlink):
        self.kind = kind
        self.maxvalue = maxvalue
        self.name = name
        # 真实实现里是内核对象句柄；这里没有跨进程对象，给个稳定值即可
        self.handle = id(self)
        self._cond = threading.Condition(threading.Lock())
        self._value = value
        self._owner = None
        self._depth = 0
        if unlink:
            # 命名信号量本就不存在，无所谓「立即 unlink」，留个痕迹让 sem_unlink 幂等
            self.name = None

    def acquire(self, block=True, timeout=None):
        me = threading.get_ident()
        with self._cond:
            if self.kind == _RECURSIVE_MUTEX and self._owner == me:
                self._depth += 1
                return True
            deadline = None if timeout is None else time.monotonic() + timeout
            while self._value <= 0:
                if not block:
                    return False
                if deadline is None:
                    self._cond.wait()
                    continue
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return False
                self._cond.wait(remaining)
                if self._value <= 0 and time.monotonic() >= deadline:
                    return False
            self._value -= 1
            self._owner = me
            self._depth = 1
            return True

    def release(self):
        me = threading.get_ident()
        with self._cond:
            if self.kind == _RECURSIVE_MUTEX:
                if self._owner != me:
                    raise AssertionError("attempt to release recursive lock not owned by thread")
                self._depth -= 1
                if self._depth > 0:
                    return
                self._owner = None
            else:
                if self._value >= self.maxvalue:
                    raise ValueError("semaphore or lock released too many times")
                self._owner = None
                self._depth = 0
            self._value += 1
            self._cond.notify()

    def __enter__(self):
        return self.acquire()

    def __exit__(self, *args):
        return self.release()

    def _count(self):
        return self._depth

    def _is_mine(self):
        return self._owner == threading.get_ident() and self._depth > 0

    def _get_value(self):
        return self._value

    def _is_zero(self):
        return self._value == 0

    def _after_fork(self):
        # 这里没有真正的 fork 语义；重建条件变量，免得继承到一把处于加锁态的 threading 锁
        self._cond = threading.Condition(threading.Lock())

    @staticmethod
    def _rebuild(handle, kind, maxvalue, name):
        return SemLock(kind, 1, maxvalue, name, False)


def sem_unlink(name):
    """命名信号量不存在，调用方（synchronize.py 的 finalizer）只要不抛就行"""
    return None
'''


def log(text: str) -> None:
    print(text, flush=True)


def rmtree(path: Path) -> None:
    if path.is_dir():
        shutil.rmtree(path, ignore_errors=True)


def normalize(name: str) -> str:
    """PEP 503 的包名归一：StrEnum / strenum / str_enum 是同一个包"""
    return re.sub(r"[-_.]+", "-", name).strip().lower()


def requirement_name(spec: str) -> str:
    match = NAME_END.search(spec)
    return normalize(spec[: match.start()] if match else spec)


# ---------------------------------------------------------------- core


def fetch_core(abi: str, repo: str, tag: str, work: Path) -> Path:
    """下载并解包内核，两步都以 .maafw 里已有的产物为准，不重复拉"""
    asset = f"agent-core-{CORE_PY}-{abi}.tar.gz"
    archive = work / asset
    if not archive.is_file() or archive.stat().st_size == 0:
        url = CORE_URL.format(repo=repo, tag=tag, asset=asset)
        log(f"download {url}")
        part = archive.with_suffix(archive.suffix + ".part")
        with urllib.request.urlopen(url, timeout=120) as response, part.open("wb") as sink:
            shutil.copyfileobj(response, sink)
        part.replace(archive)
    else:
        log(f"cached   {archive.name}")

    # 归档里就是 <abi>/bundle/，各 ABI 天然不撞；重解时只清自己那棵，
    # 清整个 core-<tag> 会把同目录下另一个 ABI 一起端了
    extracted = work / f"core-{tag}"
    bundle = extracted / abi / "bundle"
    if not (bundle / CORE_MANIFEST).is_file():
        rmtree(bundle.parent)
        with tarfile.open(archive) as tar:
            tar.extractall(extracted, filter="tar")
    return bundle


def local_core(value: str, abi: str, stack: list[tempfile.TemporaryDirectory]) -> Path:
    """--core 收目录或 tar.gz；归档解到临时目录，退出时清掉"""
    path = Path(value).resolve()
    if path.is_dir():
        return path / abi / "bundle"
    if not path.is_file():
        raise SystemExit(f"--core does not exist: {path}")
    staging = tempfile.TemporaryDirectory(prefix="agent-core-")
    stack.append(staging)
    log(f"extract  {path.name}")
    with tarfile.open(path) as tar:
        tar.extractall(staging.name, filter="tar")
    return Path(staging.name) / abi / "bundle"


def read_manifest(bundle: Path) -> dict:
    path = bundle / CORE_MANIFEST
    if not path.is_file():
        raise SystemExit(f"not an agent core, {CORE_MANIFEST} is missing: {bundle}")
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------------------------------------------------------- requirements


@dataclass(frozen=True)
class Requirement:
    name: str  # 归一化后的名字
    spec: str  # 去掉 marker 的部分，原样交给 pip
    marker: str | None


def load_marker_evaluator():
    """packaging 不在 stdlib 里；pip 自带一份 vendored 的，够用就不强求外部安装"""
    try:
        from packaging.markers import Marker  # type: ignore
        return Marker
    except ImportError:
        pass
    try:
        from pip._vendor.packaging.markers import Marker  # type: ignore
        return Marker
    except ImportError as error:
        raise SystemExit(
            "packaging is required to evaluate environment markers: pip install packaging"
        ) from error


def marker_env(abi: str, manifest: dict) -> dict[str, str]:
    """CPython 3.13 起 Android 是独立平台（PEP 738），sys_platform 是 android 而非 linux"""
    python = manifest["python"]
    return {
        "sys_platform": "android",
        "platform_system": "Android",
        "platform_machine": MACHINES[abi],
        "os_name": "posix",
        "python_version": ".".join(python.split(".")[:2]),
        "python_full_version": python,
        "platform_python_implementation": "CPython",
        "implementation_name": "cpython",
    }


def parse_requirement(line: str) -> Requirement | None:
    """注释与空行返回 None；选项行直接失败"""
    text = line.split(" #", 1)[0].strip()
    if not text or text.startswith("#"):
        return None
    if text.startswith("-"):
        raise SystemExit(
            f"unsupported requirements option line: {text}\n"
            "(nested -r and --hash are not implemented; expand it or use --require)"
        )
    spec, _, marker = text.partition(";")
    spec = spec.strip()
    return Requirement(requirement_name(spec), spec, marker.strip() or None)


def pinned_version(spec: str) -> str | None:
    match = re.search(r"==\s*([^\s,;\[\]]+)", spec)
    return match.group(1) if match else None


def decide(
    requirement: Requirement,
    provides: dict[str, str],
    excludes: set[str],
    environment: dict[str, str],
    marker_type,
) -> str | None:
    """返回丢弃理由；None 表示要装"""
    if requirement.marker and not marker_type(requirement.marker).evaluate(environment):
        return f"marker is false: {requirement.marker}"
    if requirement.name in provides:
        pinned = pinned_version(requirement.spec)
        supplied = provides[requirement.name]
        if pinned and pinned != supplied:
            return f"core ships {supplied} (project pins {pinned}, core wins)"
        return f"core ships {supplied}"
    if requirement.name in DEVICE_DROP:
        return "not usable on Android"
    if requirement.name in excludes:
        return "--exclude"
    return None


def plan(
    files: list[Path],
    extra: list[str],
    provides: dict[str, str],
    excludes: set[str],
    environment: dict[str, str],
) -> tuple[list[str], set[str]]:
    """筛出要装的 spec，外加「装完还要清一遍」的名字

    pip 对传递依赖的 marker 同样按构建机求值，loguru 在 Windows 上会把 colorama
    拖回来，光筛顶层挡不住。内核提供的那批不在清理名单里
    """
    marker_type = load_marker_evaluator()
    specs: list[str] = []
    unwanted: set[str] = set()

    for path in files:
        lines = path.read_text(encoding="utf-8").splitlines()
        log(f"  read {path} ({len(lines)} lines)")
        for line in lines:
            requirement = parse_requirement(line)
            if requirement is None:
                continue
            reason = decide(requirement, provides, excludes, environment, marker_type)
            if reason is None:
                specs.append(requirement.spec)
                log(f"    keep {requirement.spec}")
                continue
            log(f"    drop {requirement.spec:<28} {reason}")
            if requirement.name not in provides:
                unwanted.add(requirement.name)

    for spec in extra:
        specs.append(spec)
        log(f"    keep {spec} (--require)")
        # --require 压过前面任何丢弃理由：`--exclude pillow --require pillow==11.0.0`
        # 这种换版本写法很常规，不豁免的话装完立刻被清理那步删掉
        unwanted.discard(requirement_name(spec))
    return specs, unwanted


def pip_install(
    site: Path,
    cache: Path,
    manifest: dict,
    specs: list[str],
    extra_indexes: tuple[str, ...],
    no_deps: bool,
) -> None:
    """pip 只按 wheel 文件名的 tag 过滤、不执行构建，所以能跨平台解析

    tag 给一梯而不是一级：--platform 是精确匹配，没有 manylinux 那种兼容阶梯，
    只发 android_24 会拒掉 chaquopy-freetype 这类 tag 是 android_21 的轮子
    """
    if not specs:
        return
    command = [
        sys.executable, "-m", "pip", "install", "--quiet",
        "--target", str(site),
        "--cache-dir", str(cache / "pip"),
        "--only-binary", ":all:",
        "--python-version", ".".join(manifest["python"].split(".")[:2]),
        "--implementation", "cp",
        "--abi", manifest["pyAbiTag"],
    ]
    for api in manifest["wheelApis"]:
        command += ["--platform", f"android_{api}_{manifest['wheelAbi']}"]
    for url in extra_indexes:
        command += ["--extra-index-url", url]
    if no_deps:
        command.append("--no-deps")
    try:
        subprocess.run(command + specs, check=True)
    except subprocess.CalledProcessError as error:
        # 堆栈对排查毫无帮助，有用的是 pip 自己那几行 ERROR 与到底在装什么
        raise SystemExit(
            f"pip failed (exit {error.returncode}): {' '.join(specs)}\n"
            "usually means no Android wheel for these versions -- change the pin, "
            "add --extra-index-url, or --exclude them"
        ) from error


def prune_installed(site: Path, unwanted: set[str]) -> list[str]:
    """按 dist-info 的 RECORD 删，才能连顶层模块一起清干净

    包名与目录名对不上的（win32-setctime -> win32_setctime.py）猜路径会漏
    """
    removed: list[str] = []
    for info in sorted(site.glob("*.dist-info")):
        name, _, _ = info.name[: -len(".dist-info")].rpartition("-")
        if normalize(name) not in unwanted:
            continue
        record = info / "RECORD"
        entries = record.read_text(encoding="utf-8").splitlines() if record.is_file() else []
        for entry in entries:
            target = site / entry.split(",", 1)[0]
            if target.is_file():
                target.unlink()
        rmtree(info)
        removed.append(name)
    for directory in sorted(site.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if directory.is_dir() and not any(directory.iterdir()):
            directory.rmdir()
    return removed


# ---------------------------------------------------------------- pack


def trim_site(site: Path) -> None:
    for package, names in SITE_DROP_PACKAGES.items():
        for name in names:
            rmtree(site / package / name)
    for cache in list(site.rglob("__pycache__")):
        rmtree(cache)
    for name in SITE_DROP_DIRS:
        for directory in list(site.rglob(name)):
            if directory.is_dir():
                rmtree(directory)


def pack_site_packages(site: Path) -> tuple[int, list[str]]:
    """纯 Python 的包收进 pure.zip 交 zipimport，带 .so 的留磁盘

    只用标准 zipimport，所以混装的包（py + so）只能整个留在磁盘上；
    dlopen 要真实路径，zip 里的读不出来
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


def write_multiprocessing_shim(site: Path) -> None:
    """Android 的 bionic 没有 POSIX 命名信号量，CPython 因此不编译 `_multiprocessing`

    典型受害者是 loguru 的 `enqueue=True`：它只要一个 SimpleQueue，而 SimpleQueue 只要一把锁
    """
    (site / "_multiprocessing.py").write_text(MULTIPROCESSING_SHIM, encoding="utf-8")


# ---------------------------------------------------------------- main


def build(core: Path, out: Path, abi: str, options, work: Path) -> None:
    manifest = read_manifest(core)
    if manifest["abi"] != abi:
        raise SystemExit(f"core declares abi {manifest['abi']}, expected {abi}: {core}")
    log(f"-- {abi} -- core CPython {manifest['python']}")

    bundle = out / abi / "bundle"
    site = bundle / "site-packages"
    rmtree(bundle)
    bundle.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(core, bundle)

    specs, unwanted = plan(
        options.requirement_files,
        options.require,
        manifest["provides"],
        options.excludes,
        marker_env(abi, manifest),
    )
    pip_install(site, work, manifest, specs, options.indexes, options.no_deps)
    removed = prune_installed(site, unwanted)
    if removed:
        log(f"  pruned re-added by pip under build-host markers: {', '.join(removed)}")

    trim_site(site)
    site_count, native = pack_site_packages(site)
    # 必须在打包之后：这份补丁要留在磁盘上，收进 pure.zip 就不是原先验过的布局了
    write_multiprocessing_shim(site)

    files = [path for path in bundle.rglob("*") if path.is_file()]
    size = sum(path.stat().st_size for path in files) / 1048576
    log(f"  zipped {site_count} entries; kept on disk (native): {', '.join(native) or 'none'}")
    log(f"  {len(files)} files / {size:.1f} MB -> {bundle}")
    if (site / CHAQUOPY_LIB).is_dir():
        log(
            "  ! Chaquopy native packages present, add "
            f"{{bundle}}/site-packages/{CHAQUOPY_LIB} to LD_LIBRARY_PATH in the profile"
        )
        log("    (PIL's _imaging and friends carry no RUNPATH and will fail to import)")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--out", required=True, help="output root, i.e. the profile's agent.sourceDir")
    parser.add_argument("--abi", action="append", choices=sorted(MACHINES), help="default arm64-v8a")
    parser.add_argument("--requirements", action="append", default=[], help="requirements file, repeatable")
    parser.add_argument("--require", action="append", default=[], help="extra dependency, unfiltered, repeatable")
    parser.add_argument("--exclude", action="append", default=[], help="package name to skip, repeatable")
    parser.add_argument(
        "--no-deps", action="store_true",
        help="use when requirements is a full lock (uv export / pip freeze): stops pip from "
             "resolving transitive deps under build-host markers",
    )
    parser.add_argument(
        "--extra-index-url", action="append", default=[],
        help=f"additional index; {EXTRA_INDEX} is always included",
    )
    parser.add_argument("--core", help="local core directory or tar.gz instead of downloading")
    parser.add_argument("--core-repo", default=CORE_REPO, help=f"default {CORE_REPO}")
    parser.add_argument("--core-tag", default=CORE_TAG, help=f"default {CORE_TAG}")
    parser.add_argument("--work", help=f"download and unpack directory, default {WORK_DIR}")
    args = parser.parse_args()

    args.requirement_files = [Path(item).resolve() for item in args.requirements]
    for path in args.requirement_files:
        if not path.is_file():
            raise SystemExit(f"requirements file does not exist: {path}")
    args.indexes = (EXTRA_INDEX, *args.extra_index_url)
    args.excludes = {normalize(name) for name in args.exclude}

    out = Path(args.out).resolve()
    work = Path(args.work).resolve() if args.work else WORK_DIR
    work.mkdir(parents=True, exist_ok=True)

    staging: list[tempfile.TemporaryDirectory] = []
    try:
        for abi in args.abi or ["arm64-v8a"]:
            core = (
                local_core(args.core, abi, staging)
                if args.core
                else fetch_core(abi, args.core_repo, args.core_tag, work)
            )
            build(core, out, abi, args, work)
    finally:
        for item in staging:
            item.cleanup()

    log(f"agent.sourceDir = {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
