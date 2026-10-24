#!/usr/bin/env python3
"""Patch activity game Mekorama agar memanggil menu mod saat onCreate.

Target: smali_classes2/com/martinmagni/mekorama/Mekorama.smali
Sisipan (tepat setelah invoke-super onCreate di dalam method onCreate):

    invoke-static {p0}, Lcom/hozinking/mekomod/Menu;->attach(Landroid/app/Activity;)V

Skrip ini IDEMPOTEN dan GAGAL KERAS (exit 1) bila jangkar tidak ditemukan,
supaya perubahan struktur APK versi lain tidak lolos diam-diam.
Pemakaian: patch_smali.py <folder hasil apktool decode>
"""
import sys
from pathlib import Path

MARKER = "Lcom/hozinking/mekomod/Menu;->attach"
METHOD_ANCHOR = ".method public onCreate(Landroid/os/Bundle;)V"
SUPER_ANCHOR = "invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V"
INSERT = "    invoke-static {p0}, Lcom/hozinking/mekomod/Menu;->attach(Landroid/app/Activity;)V"


def main() -> int:
    if len(sys.argv) != 2:
        print("pakai: patch_smali.py <folder decode apktool>", file=sys.stderr)
        return 2
    target = Path(sys.argv[1]) / "smali_classes2/com/martinmagni/mekorama/Mekorama.smali"
    if not target.is_file():
        print(f"GAGAL: file tidak ditemukan: {target}", file=sys.stderr)
        return 1
    text = target.read_text(encoding="utf-8")
    if MARKER in text:
        print("Sudah ter-patch, lewati.")
        return 0
    lines = text.split("\n")
    in_method = False
    for i, line in enumerate(lines):
        if line.startswith(METHOD_ANCHOR):
            in_method = True
            continue
        if in_method and line.startswith(".end method"):
            break
        if in_method and SUPER_ANCHOR in line:
            lines.insert(i + 1, "")
            lines.insert(i + 2, INSERT)
            target.write_text("\n".join(lines), encoding="utf-8")
            print(f"Patch OK: {INSERT.strip()} disisipkan setelah baris {i + 1}")
            return 0
    print("GAGAL: jangkar onCreate/invoke-super tidak ditemukan — struktur APK berubah?", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
