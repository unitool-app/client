#!/usr/bin/env python3
# Writes unitool-kit/src/commonTest/.../openssl/OpensslFixtures.kt with the real `openssl enc`
# (cr-restore D618). Run from the repository root:
#   python3 scripts/openssl-fixtures.py unitool-kit/src/commonTest/kotlin/cx/m42/unitool/kit/openssl/OpensslFixtures.kt
import subprocess, os, base64
PW = "пароль-p@ss 1"
def run(args, data):
    r = subprocess.run(["openssl","enc","-e"]+args, input=data, capture_output=True)
    assert r.returncode == 0, (args, r.stderr)
    return r.stdout
plain_main = "Секрет: the quick brown fox ✓\n".encode()
rows = []
def add(label, cipher, args, data, kdf="Pbkdf2", it=10000, md="sha256", salted=True, padding=True, raw=None, armored=False):
    out = run(["-"+cipher]+args+(["-a"] if armored else []), data)
    rows.append((label, cipher, kdf, it, md, salted, padding, raw, armored, out, data))
modes = ["cbc","ecb","cfb","cfb1","cfb8","ofb","ctr"]
for bits in (128,192,256):
    for m in modes:
        c=f"aes-{bits}-{m}"
        add(f"{c} pbkdf2", c, ["-pbkdf2","-iter","1000","-pass","pass:"+PW], plain_main, it=1000)
        add(f"{c} legacy", c, ["-pass","pass:"+PW], plain_main, kdf="Legacy")
for md in ("sha1","sha384","sha512"):
    add(f"md {md} pbkdf2", "aes-256-cbc", ["-pbkdf2","-md",md,"-pass","pass:"+PW], plain_main, md=md)
    add(f"md {md} legacy", "aes-256-cbc", ["-md",md,"-pass","pass:"+PW], plain_main, kdf="Legacy", md=md)
for n in (0,1,15,16,17,33):
    data = bytes((i*7+3)%256 for i in range(n))
    add(f"size {n}", "aes-256-cbc", ["-pbkdf2","-pass","pass:"+PW], data)
add("default iter -a", "aes-256-cbc", ["-pbkdf2","-pass","pass:"+PW], plain_main, armored=True)
r = subprocess.run(["openssl","enc","-e","-aes-256-cbc","-pbkdf2","-pass","pass:"+PW,"-a","-A"], input=plain_main*5, capture_output=True); assert r.returncode==0
rows.append(("-a -A one line","aes-256-cbc","Pbkdf2",10000,"sha256",True,True,None,True,r.stdout,plain_main*5))
add("long -a lines", "aes-256-cbc", ["-pbkdf2","-pass","pass:"+PW], plain_main*5, armored=True)
add("iter 600000", "aes-256-cbc", ["-pbkdf2","-iter","600000","-pass","pass:"+PW], plain_main, it=600000)
add("nosalt pbkdf2", "aes-256-cbc", ["-pbkdf2","-nosalt","-pass","pass:"+PW], plain_main, salted=False)
add("nosalt legacy", "aes-128-cbc", ["-nosalt","-pass","pass:"+PW], plain_main, kdf="Legacy", salted=False)
K="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"; IV="f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
add("raw key cbc", "aes-256-cbc", ["-K",K,"-iv",IV], plain_main, raw=(K,IV))
add("raw key ctr", "aes-256-ctr", ["-K",K,"-iv",IV], plain_main, raw=(K,IV))
add("raw key ecb", "aes-256-ecb", ["-K",K], plain_main, raw=(K,""))
add("nopad", "aes-256-cbc", ["-pbkdf2","-nopad","-pass","pass:"+PW], bytes(range(32)), padding=False)
ver = subprocess.run(["openssl","version"],capture_output=True,text=True).stdout.strip().split(" (")[0]
def kstr(b): return '"' + base64.b64encode(b).decode() + '"'
out = []
out.append("package cx.m42.unitool.kit.openssl\n")
out.append("/**")
out.append(f" * Made once by the real `openssl enc` ({ver}) on 2026-10-27 by `scripts/openssl-fixtures.py`")
out.append(" * (cr-restore D618), so every target, the native binary's included, opens what the tool wrote.")
out.append(" * `desktopTest` checks the same against the tool live (`OpensslInteropTest`). Base64 of the bytes,")
out.append(" * except where the case is the `-a` text itself.")
out.append(" */")
out.append("internal object OpensslFixtures {")
out.append(f"    const val PASSWORD: String = \"{PW}\"\n")
out.append("    class Case(")
out.append("        val label: String, val cipher: String, val kdf: OpensslEnc.Kdf, val iterations: Int, val digest: String,")
out.append("        val salted: Boolean, val padding: Boolean, val rawKey: String?, val rawIv: String?,")
out.append("        val armored: Boolean, val file: String, val plaintext: String,")
out.append("    )\n")
out.append("    val cases: List<Case> = listOf(")
for (label,c,kdf,it,md,salted,padding,raw,armored,o,data) in rows:
    filev = '"""' + o.decode() + '"""' if armored else kstr(o)
    rk = f'"{raw[0]}"' if raw else "null"; ri = f'"{raw[1]}"' if raw else "null"
    out.append(f'        Case("{label}", "{c}", OpensslEnc.Kdf.{kdf}, {it}, "{md}", {str(salted).lower()}, {str(padding).lower()}, {rk}, {ri}, {str(armored).lower()}, {filev}, {kstr(data)}),')
out.append("    )")
out.append("}")
import sys
open(sys.argv[1] if len(sys.argv) > 1 else "OpensslFixtures.kt","w").write("\n".join(out)+"\n")
print(len(rows), ver)
