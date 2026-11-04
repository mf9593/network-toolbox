#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把第三方预编译 .so 的 LOAD 段对齐到 16KB，满足 Android 15+ 16KB 页要求。

背景：
  Android 15 起部分设备使用 16KB 内存页，要求 ELF 的 PT_LOAD 段
  p_align >= 0x4000，并且 p_offset 与 p_vaddr 对 0x4000 同余。
  项目里 librtmp-jni.so 来自 rtmp-client 3.2.0（ExoPlayer RTMP 扩展的
  传递依赖），它是按 4KB 页编译的，系统会提示「应用不符合 16KB 对齐要求」。

做法：
  在需要对齐的 LOAD 段前面插入若干字节的零填充，让 p_offset 与 p_vaddr
  同余，再把 p_align 改成 0x4000。虚拟地址完全不变，运行时行为不变；
  同时同步修正程序头/节头里的文件偏移。

用法：
  python3 tools/align16k.py <a.so> [b.so ...]
  python3 tools/align16k.py --dir <包含 lib 的目录>   # 递归处理所有 ELF64 .so
"""

import os
import struct
import sys

PT_LOAD = 1
PAGE = 0x4000
SHT_NOBITS = 8


def _is_elf64_shared(path):
    try:
        with open(path, "rb") as f:
            return f.read(6) == b"\x7fELF\x02\x01"
    except OSError:
        return False


def patch_elf(path):
    """返回 True 表示文件被修改，False 表示无需修改/不是 ELF64。"""
    with open(path, "rb") as f:
        data = bytearray(f.read())
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        return False

    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    if e_phentsize != 56:
        return False

    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        (
            p_type, p_flags, p_offset, p_vaddr, p_paddr,
            p_filesz, p_memsz, p_align,
        ) = struct.unpack_from("<IIQQQQQQ", data, off)
        phdrs.append({
            "off": off, "type": p_type, "flags": p_flags,
            "offset": p_offset, "vaddr": p_vaddr, "paddr": p_paddr,
            "filesz": p_filesz, "memsz": p_memsz, "align": p_align,
        })

    loads = [p for p in phdrs if p["type"] == PT_LOAD]
    if not loads:
        return False
    already_ok = all(
        p["align"] >= PAGE and p["offset"] % PAGE == p["vaddr"] % PAGE
        for p in loads
    )
    if already_ok:
        return False

    # 按文件偏移从小到大补填充，记录「原始偏移 -> 需要插入的字节数」
    loads.sort(key=lambda p: p["offset"])
    inserts = []  # (原始插入位置, 插入字节数, 插入后的位置)
    shift = 0
    for p in loads:
        new_offset = p["offset"] + shift
        pad = (p["vaddr"] - new_offset) % PAGE
        if pad:
            inserts.append((p["offset"], pad, new_offset))
            shift += pad
        p["align"] = PAGE

    def shift_at(orig):
        """原始文件偏移 orig 在新文件里的偏移"""
        total = 0
        for ins_orig, pad, _ in inserts:
            if ins_orig <= orig:
                total += pad
        return orig + total

    # 程序头：偏移落在插入点之后的都要后移
    for p in phdrs:
        p["offset"] = shift_at(p["offset"])
        struct.pack_into("<IIQQQQQQ", data, p["off"],
                         p["type"], p["flags"], p["offset"], p["vaddr"],
                         p["paddr"], p["filesz"], p["memsz"], p["align"])

    # 节头表：位置和每节的 sh_offset 一起后移（节头运行时不参与，只为文件自洽）
    new_shoff = shift_at(e_shoff)
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if off + 64 > len(data):
            break
        sh_type = struct.unpack_from("<I", data, off + 4)[0]
        sh_offset = struct.unpack_from("<Q", data, off + 0x18)[0]
        if sh_offset == 0:
            continue
        new_offset = shift_at(sh_offset)
        struct.pack_into("<Q", data, off + 0x18, new_offset)

    struct.pack_into("<Q", data, 0x28, new_shoff)

    # 从后往前插，避免下标错位
    for orig, pad, new_pos in reversed(inserts):
        data[new_pos:new_pos] = b"\x00" * pad

    mode = os.stat(path).st_mode
    with open(path, "wb") as f:
        f.write(data)
    os.chmod(path, mode)
    return True


def verify(path):
    with open(path, "rb") as f:
        data = f.read()
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    ok = True
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _, p_offset, p_vaddr, _, _, _, p_align = struct.unpack_from(
            "<IIQQQQQQ", data, off)
        if p_type != PT_LOAD:
            continue
        if p_align < PAGE or p_offset % PAGE != p_vaddr % PAGE:
            ok = False
            print("  [FAIL] LOAD offset=0x%x vaddr=0x%x align=0x%x"
                  % (p_offset, p_vaddr, p_align))
    return ok


def main(argv):
    targets = []
    args = argv[1:]
    if not args:
        print(__doc__)
        return 1
    if args[0] == "--dir":
        for root, _, files in os.walk(args[1]):
            for name in files:
                if name.endswith(".so"):
                    targets.append(os.path.join(root, name))
    else:
        targets = args

    changed = 0
    failed = 0
    for path in targets:
        if not _is_elf64_shared(path):
            continue
        try:
            if patch_elf(path):
                changed += 1
                ok = verify(path)
                print(("  [OK] %s" if ok else "  [FAIL] %s") % path)
                if not ok:
                    failed += 1
        except Exception as exc:  # noqa: BLE001
            failed += 1
            print("  [ERROR] %s: %s" % (path, exc))
    if changed == 0:
        print("  已是 16KB 对齐，无需处理")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
