#!/usr/bin/env bash
#
# Prints the key a promo code gets in the hashed table in UnitoolHost.kt (done/idea/09, D256).
#
#   ./scripts/promo-hash.sh <code> [<code> …]      # one line per code: hash, then the normalised code
#   PROMO_SALT=other: ./scripts/promo-hash.sh <code>
#
# The table holds hashes so that the APK carries no code that `strings` would print. The codes
# themselves go in .secrets/promo-codes.md, next to what each one opens: that list is what the
# stores' reviewers are given (RELEASING.md), and what a person reads codes off.
#
# Compared as PromoCode does. Most codes are normalised like LocalPromoCodes.normalise — letters (any
# script) and decimal digits, upper-cased — so "secret 2026", "SECRET-2026" and "secret2026" print
# the same key. A code in brackets — "{ssh-keys}", "[ssh-keys]" — is exact: hashed as written, only
# the spaces around it dropped, so it opens nothing for a near miss. PromoCodesTest is what catches
# a row that no longer matches its code.
set -euo pipefail

salt="${PROMO_SALT:-unitool:}"   # PROMO_SALT in UnitoolHost.kt
if [ $# -eq 0 ]; then
  echo "usage: $0 <code> [<code> …]" >&2
  exit 2
fi

python3 - "$salt" "$@" <<'PY'
import hashlib
import sys
import unicodedata

salt, codes = sys.argv[1], sys.argv[2:]


def normalise(code):
    # Kotlin's Char.isLetterOrDigit: a letter of any kind (L*) or a decimal digit (Nd) — not
    # Python's isalnum, which also takes ² and Ⅻ.
    kept = (c for c in code if unicodedata.category(c)[0] == "L" or unicodedata.category(c) == "Nd")
    return "".join(kept).upper()


def exact(code):
    t = code.strip()
    return len(t) > 2 and ((t[0] == "{" and t[-1] == "}") or (t[0] == "[" and t[-1] == "]"))


for code in codes:
    key = code.strip() if exact(code) else normalise(code)
    if not key:
        sys.exit(f"'{code}' has no letters or digits: nobody could type it")
    print(hashlib.sha256((salt + key).encode("utf-8")).hexdigest(), key, "(exact)" if exact(code) else "")
PY
