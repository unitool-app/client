#!/bin/bash
# The linker for the static `unitool` (`:unitool-cli:app:installStatic`). Kotlin/Native has no switch
# for a fully static Linux executable: it always passes a dynamic loader, `-export-dynamic` and
# `-lgcc_s`, and with `-static` added on top it links a hybrid that crashes at start (D291). This
# script stands in for `ld.lld` and rewrites that line into a static one:
#
#   * `-static`, and no `-dynamic-linker`, `-export-dynamic`, `-Bdynamic`, `--as-needed` or `-lgcc_s`;
#   * `crtbeginT.o` (the static start file) instead of `crtbegin.o`;
#   * the whole of libpthread. Static glibc 2.19 refers to pthread functions weakly, so only the
#     ones something asks for by name are linked in. The runtime's first mutex call then went to
#     address 0, and that was the crash D291 recorded;
#   * the libraries in one group, because static archives depend on each other in a circle;
#   * `-s` at the very end: no symbol table in the shipped file. lld keeps the last of `-s` and `-S`,
#     and Kotlin/Native passes `-S`, so it is dropped and `-s` goes last.
#
# The real `ld.lld` is the one in the same Kotlin/Native dependencies folder as the `--sysroot`
# the compiler passes.
set -euo pipefail

out=(-static)
libs=()
sysroot=""
skip=0
for a in "$@"; do
  if [ "$skip" = 1 ]; then skip=0; continue; fi
  case "$a" in
    -dynamic-linker) skip=1 ;;
    -S|-export-dynamic|-Bdynamic|-Bstatic|--as-needed|--no-as-needed|-lgcc_s) ;;
    --sysroot=*) sysroot="${a#--sysroot=}"; out+=("$a") ;;
    -l*) libs+=("$a") ;;
    */crtbegin.o) out+=("${a%crtbegin.o}crtbeginT.o") ;;
    *) out+=("$a") ;;
  esac
done
out+=(--whole-archive -lpthread --no-whole-archive --start-group "${libs[@]}" -lgcc -lgcc_eh -lc --end-group -s)

[ -n "$sysroot" ] || { echo "static-ld.sh: no --sysroot on the link line" >&2; exit 1; }
deps="${sysroot%%/x86_64-unknown-linux-gnu-gcc-*}"
ld=$(ls -d "$deps"/llvm-*-x86_64-linux-*/bin/ld.lld 2>/dev/null | sort -V | tail -1)
[ -x "$ld" ] || { echo "static-ld.sh: no ld.lld under $deps" >&2; exit 1; }
exec "$ld" "${out[@]}"
