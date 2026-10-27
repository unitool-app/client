package cx.m42.unitool.kit.openssl

/**
 * Made once by the real `openssl enc` (OpenSSL 3.0.13 30 Jan 2024) on 2026-10-27 by `scripts/openssl-fixtures.py`
 * (cr-restore D618), so every target, the native binary's included, opens what the tool wrote.
 * `desktopTest` checks the same against the tool live (`OpensslInteropTest`). Base64 of the bytes,
 * except where the case is the `-a` text itself.
 */
internal object OpensslFixtures {
    const val PASSWORD: String = "пароль-p@ss 1"

    class Case(
        val label: String, val cipher: String, val kdf: OpensslEnc.Kdf, val iterations: Int, val digest: String,
        val salted: Boolean, val padding: Boolean, val rawKey: String?, val rawIv: String?,
        val armored: Boolean, val file: String, val plaintext: String,
    )

    val cases: List<Case> = listOf(
        Case("aes-128-cbc pbkdf2", "aes-128-cbc", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1+N+TsUE8lgFIdxSADMjJW6SK4FiqDwoIvln+1uk0o4Xbl9uN2hDz4pGmcZ8xiLhCu/7OeIFvzQnw==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cbc legacy", "aes-128-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX18RWO/4wwNS59nb7ho9QysFpYEBT4upMAuFeT/OepAgGWOHu+q9trMd6zeL6NscMdpDXj7xg1uuRA==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-ecb pbkdf2", "aes-128-ecb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/nPpgIODjsNlzOfsLtQFV2xwAyQOVaBp+AMVJKP0T0ysOYzi6KtDZzqQEKoHyd/BIWIaT20u9jug==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-ecb legacy", "aes-128-ecb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX18geAqI23ZzHkJhCHuDTpfWnoI3n9/xGCupe/laojvI+tvmz2m20wa2MtkpnE2Jk2tDNHfKMmLiHQ==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cfb pbkdf2", "aes-128-cfb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX19C/T/fsBVZ8/B8m5q7Q06UKRyoDW7YTthbRtGqWzNmFDjpEmo24PWidzlbntR0", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cfb legacy", "aes-128-cfb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/KSyXHnArwAuHIMYSmqQ0guOvkuszNEUbKxvl/12cdEBlOJoXfa4Ne/iWAm+Nh", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cfb1 pbkdf2", "aes-128-cfb1", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/MLdKygTSImt9By6ZtbNMQ0c+XaLp8jowCRMj2YAQJbluyTvYCzxciF6FYf0Ce", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cfb1 legacy", "aes-128-cfb1", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX197xlYOiyvamLxJfefKkHbaioe5doIrEptjh7c/r2ZcVttvU/XZhwdFa0XE0ocl", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cfb8 pbkdf2", "aes-128-cfb8", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX18+Uv6lz+EL4TyYVDTpzu6BdEV9gvKYGJfsxaFjrq/z3vlqM5DGcnD1cfRE2Cg2", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-cfb8 legacy", "aes-128-cfb8", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1+iXDntAvkPKiSZx9tCZjzTTAx6wh5xXqPgNAjsvEufRI8ufDcVmRO8KKSk0zaz", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-ofb pbkdf2", "aes-128-ofb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX181s/qKpVKl5svA/41NXKpW10Otg5ovt+YvwVyhXG4PP1HN8OJvIUfJp0Uqy87E", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-ofb legacy", "aes-128-ofb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1+SBi3edoTcD1/khzvjSSZqkCduPAyigR79gVJKcvDKFZupgoAjckQ6qOd/Vicz", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-ctr pbkdf2", "aes-128-ctr", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/NrnAf3sm29u0DSlziEqEHDw/nbGH5VmPOlYwiWu+znFPaMh0mRSb13eOzgDxj", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-128-ctr legacy", "aes-128-ctr", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX18uPidQAZFh77Tz68Qlp2pwoI8eGPZWwYiQIXdDq5d+iEgm5+L4B22c1ISdApDd", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cbc pbkdf2", "aes-192-cbc", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX18N2N2/Y+Mt0jafpyidDkwcl2tt9OVmpxvKuBgXWaJ1pAdkw7diUp4PP1y4pBq62TzVKKDBaV6qTA==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cbc legacy", "aes-192-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/bnhMJ1sNMCdW7uy9UjqrTpYae84n7CK5mAIIfzNNZj2eDbDpRIS1iNfiS3hQgnEOifN6duDEgtw==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-ecb pbkdf2", "aes-192-ecb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1815XtuEKJ8x/fTx38EWOJ2ewM8KnMIz5rWpVJIaQlV8odztDwp63TazeaXYCIRCqeOpL289ZwZXw==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-ecb legacy", "aes-192-ecb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19LJNTtAxAVU0czCnAj1SY0DaF+o7i9taxl8FtnwJT8qWZvuwfbsYFOQURzTAeS/6qZtgoTqfkENg==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cfb pbkdf2", "aes-192-cfb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX19OuXyX13FOhyX2g2bAg6fuEqL4XN1aBlRBe4JNLu86+S1wC+3rYyP31B+m/a3D", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cfb legacy", "aes-192-cfb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19jh7TOfdoLPJlcmgFI73dZfhMGO+ydoJBhsU4AShkjy4z7PRXx0HbeNhIML84e", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cfb1 pbkdf2", "aes-192-cfb1", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX18GsAu7dYNP7woqRfAMFnfVBDVQr2W+XGnbGDqQT2fYxixtzGYjvw8M98XxgwHc", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cfb1 legacy", "aes-192-cfb1", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX18GZ8X552AzzM9JWyfTozorAgSUHomYwoNCH6HVKjXWhhy9v4QOwHDZ6hYNdLZl", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cfb8 pbkdf2", "aes-192-cfb8", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX19n0K3yMQ/19PGpg7H/OBYB5OFabacdi6ICN2bbZY6/Ftr7bqhsUBYDhEqK6ncr", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-cfb8 legacy", "aes-192-cfb8", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/haW9uB0QfOvZydabwPOnUglDWeWWh7FEQsrLQ5QqyBpLN5kPKMjDZXe0lCfgv", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-ofb pbkdf2", "aes-192-ofb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX189NQb2b0QRvV2TrNC3h9d1k4XwlCh2E+HSXalJOWpR+v3SV9br4FNfbH8pr/T/", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-ofb legacy", "aes-192-ofb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/2XoboyoTrtxyv3UIzUKjWAz0ASESaUW2+He+CEIPfTMWI8Lc/soIjXVm7Ex3h", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-ctr pbkdf2", "aes-192-ctr", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/iS9XLDYyFTYhFd2RomaWHolSEayAhpbdfl1ilqh4MjgDBd4il/U6VQQwxqmW7", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-192-ctr legacy", "aes-192-ctr", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1+zIUqCwewg+o4j2+yGEBbh5KV4Ppv7Z0G1Q5ljrlkPCB9L04KhXzXAwTfwjZLt", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cbc pbkdf2", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/aJevgKvjNJcIiZtCWADE+1+eTLyw5H3VfJCgVOpQge5TdO3QtLC61ie9AKsylb6GXU38xhXiTcw==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cbc legacy", "aes-256-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX18tOYZDeZWimX4I5tgyG0FGxuMIb+AMxvr5X/2EC6YAuzoXkkCD0e4o+Xh2r3g2Mf81GtRNrp7U2g==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-ecb pbkdf2", "aes-256-ecb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX19A5y6MDr0c+Mbfl27g3GKaJb5JGOE8JZkvOR62/YZZIXoc4Ri5aOgTZhNq7SX5txPiFy4U6uR5lQ==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-ecb legacy", "aes-256-ecb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19yR0XGR/yOHNAbS4FsHSDPUfq1ONKM9jey9k42JdZiAfZ3vl+O/YH0ImnMLnNrRib0v1vEUD6Rnw==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cfb pbkdf2", "aes-256-cfb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX19Fqc62CLhf87yxLfMNh9uL/4PphAzPazgH+lRsIthLnSXo2L+6rWApHsgeomLz", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cfb legacy", "aes-256-cfb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19gPXBMBlT5nqVF3GZlM9RI3TrvNYYBnpy1dvwzTRrElhOFF2cz9qLESm0Joy3A", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cfb1 pbkdf2", "aes-256-cfb1", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/bYW4ZuGIMM51Q8Xsm1qRQdc38t7p2Pp9KKc2pSS0xYuLe5PBF8vV7LtFWRdk4", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cfb1 legacy", "aes-256-cfb1", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/vHjxmRO3/bG8p4J+Tt4Cw/f9ydkOltkbjz+vzcYwqLWUBnssFoBUXIPY8HuYu", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cfb8 pbkdf2", "aes-256-cfb8", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX1/JGGkvxyB4wrn0Fl8+WbMnwBm5KY1ShJZ63GFYiHAIBUag5Jn2Gd9NnpAZ2Pn6", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-cfb8 legacy", "aes-256-cfb8", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19Q4GS+ksO5/dcQdpxzb+61XbrnaJ40zLS9L3tJ7MhEs+L800Ym3pQCYLVtepzA", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-ofb pbkdf2", "aes-256-ofb", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX19ONmBfmvJk1S7dlyKXQDEiJ2wSeXo9Eg+XfYrW9MoH9e1E+gc5HrEKVPi5Rwzu", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-ofb legacy", "aes-256-ofb", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1+mSO5HtSOVmT1thaCta8mbX/7n1LICLPISJ3VWfbEF7TYa1Lq6ZyboLfNLhUTI", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-ctr pbkdf2", "aes-256-ctr", OpensslEnc.Kdf.Pbkdf2, 1000, "sha256", true, true, null, null, false, "U2FsdGVkX18ikGuS3ncnr6fXSSwz17g+rLkPbSb+dYiYanzXwNZi4D/Kkw3tp1xscQUwdcb4", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("aes-256-ctr legacy", "aes-256-ctr", OpensslEnc.Kdf.Legacy, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19Af1tk56VJQ0cZ7UkiRHid9GdjLF8qHGUAR1d8yKl8LpOkesFwwX+Jg5u7qeuW", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("md sha1 pbkdf2", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha1", true, true, null, null, false, "U2FsdGVkX1+x+sU4QyyOlEKvJ2+4/zy/PY6fnURVLWdt5Yc4KYlb4Z9iB46jxhBWTh0RTe+8ACc/IIzbEeZHKA==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("md sha1 legacy", "aes-256-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha1", true, true, null, null, false, "U2FsdGVkX1/wS4zDtC2FQ+8Z0bN8oGMsWEUTOp8vpqFIIAETFgNvFSqTPx1i0MlxMk4BAjciMxoHj/PM3XtZ9A==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("md sha384 pbkdf2", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha384", true, true, null, null, false, "U2FsdGVkX1/4y4d9w/Q51M0TrQNzYpZr4lgkrFdfxlLKeWCfG659ijEfzyuWAB+b/KvoWW09qGEeIo4Vpl2uZA==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("md sha384 legacy", "aes-256-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha384", true, true, null, null, false, "U2FsdGVkX19LioPr2BiVxfTKrdjdd5/O1zSPzoohIT9I43qYvERxcbC/a7DBZ58TEZ5e3l8S9z+7FakyV94l3Q==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("md sha512 pbkdf2", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha512", true, true, null, null, false, "U2FsdGVkX18AEUR58t5Y+54bAdnkRkl2Lvmc4ElS+BcYArkdOdWotl4s2aGEprvyVGoKRf4jEzEOBkGzhwk7Xg==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("md sha512 legacy", "aes-256-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha512", true, true, null, null, false, "U2FsdGVkX18fSJDkLMTvVyyUbdnE7cJPNX5MKCpSUZL6MQaDjjV5qSygi3HpHczx//PhS84uLpHa23L/BP98hw==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("size 0", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/La5hLscrwHs1QtU7v8ggIm5unTsWVyeQ=", ""),
        Case("size 1", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX18oxh1dqh7H5xwlEIvvm/8LtsAYeeFCrDY=", "Aw=="),
        Case("size 15", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/ulbEtHagDteglFlvpu80k2hd0AP3vbAE=", "AwoRGB8mLTQ7QklQV15l"),
        Case("size 16", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/fLjSbox4v5EdIDIcdyhxsdsYipQkmXueeW8samyX+/XkmQx1UNs59", "AwoRGB8mLTQ7QklQV15lbA=="),
        Case("size 17", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX1/3MAqlSIScpDGOuhJwPZntbKXB/PIs9ZJ4Ko5OKWgCELW5ZMBKjqHK", "AwoRGB8mLTQ7QklQV15lbHM="),
        Case("size 33", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, false, "U2FsdGVkX19Wr/hMEAksCWU2ZCnoMIJqOe5gwGWZkAoA/JvZnbu2fQsB17Lnb+kxQDLh+XHIOSTfMKh4MaLeAA==", "AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dzj"),
        Case("default iter -a", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, true, """U2FsdGVkX1+FXpdRbRyVdD6YtiLpUjPHGNRKiaGZZpB4EnQ8haQDG+uwX6hBO3kJ
oOP8bJkXF5C57+lMxdHIUg==
""", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("-a -A one line", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, true, """U2FsdGVkX19Mj4uzR9aL6E6uLck9SQ1ktL1YJ9OlxDQc3P+D4tiVBKvYILul5TvbdUeuV/BobdoksLrRKzqtOSXcvjsySjirLEli1Gw8hwHunF5Ch0XODRQ/pJTDPG6xLYgedxRy9Zi5S2+m+ag+S3AJ552MWXSWXRvzJfOvDZys+5Ng1Zo4WFvee8A1YTvrpSOn+73+VVI0y03Nq+uYaUWeyTD996OIamdj0hgv1532cXMLhPT+Pd3+v7oZpEjyKHMDQAlW8LuOj7xySWb3xw==""", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwrQodC10LrRgNC10YI6IHRoZSBxdWljayBicm93biBmb3gg4pyTCtCh0LXQutGA0LXRgjogdGhlIHF1aWNrIGJyb3duIGZveCDinJMK0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwrQodC10LrRgNC10YI6IHRoZSBxdWljayBicm93biBmb3gg4pyTCg=="),
        Case("long -a lines", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, null, null, true, """U2FsdGVkX196uw8iv4HWRjQ6ZcObWyVXNItphAuoKmsroJ0z9rHJ57Tak0knAVna
bUMugnrdwc2TUAwV804GZI8IrjprFyZsG4D+9mGwijMQeNQ4yZbnH56Tji9I3YZp
FIR8eUzdDkJ5XI1DLKcTVcFQHr4hZ76OCVUcWlKH5+qdkGkum4uG9ejTxCp7blvE
ziibaarVhDrBmna/Ohd7SBBxyHx1AMh7uTOV3tzTPwmELzb/ALCcjNchcjja27th
q92ypjmXtWyag3bfYJcfAQ==
""", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwrQodC10LrRgNC10YI6IHRoZSBxdWljayBicm93biBmb3gg4pyTCtCh0LXQutGA0LXRgjogdGhlIHF1aWNrIGJyb3duIGZveCDinJMK0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwrQodC10LrRgNC10YI6IHRoZSBxdWljayBicm93biBmb3gg4pyTCg=="),
        Case("iter 600000", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 600000, "sha256", true, true, null, null, false, "U2FsdGVkX1+mSZNdHtPA0jyXuUBJSKvKtRbu0Yj/MeNGie8iWPKdlWCrMCi//nkll4tKPI8uxpCm1GfPx/aRHA==", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("nosalt pbkdf2", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", false, true, null, null, false, "gvVoDICKyl0Bf7XOOS8fuc2xtgv765uvgxZFMUSXf2CU+pWDjoHaL8/LNPS2igQ2", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("nosalt legacy", "aes-128-cbc", OpensslEnc.Kdf.Legacy, 10000, "sha256", false, true, null, null, false, "uf8Ii7KQSLgDx7VanDyBPPooTPtidDJ0fMdOZS4PwmXYe3T0stuUWX/NW+hcafRy", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("raw key cbc", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff", false, "UWFto/Cddb3M2K8UGEaRxPP7mxkfOxqW3CFsaVWXNNo579RLy6ObB3pFq5TZf64a", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("raw key ctr", "aes-256-ctr", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff", false, "QqEdOPMsUUuK3DfWehIXfK9/CjWYSV/hDNU6ViRX4OHhTMPHhxI=", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("raw key ecb", "aes-256-ecb", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, true, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "", false, "8mwl9Uz3Raq+k2MWvXGzC0bs4NW3sPrA8nwMvnEszQh7+XhZ7HQP8Ljwm9hGM4J5", "0KHQtdC60YDQtdGCOiB0aGUgcXVpY2sgYnJvd24gZm94IOKckwo="),
        Case("nopad", "aes-256-cbc", OpensslEnc.Kdf.Pbkdf2, 10000, "sha256", true, false, null, null, false, "U2FsdGVkX19cgwUNr3sMJ6mssnA6L6UE/5OZ4Ewi4b+j2Uuvti1fcby7BgrrTTXV", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
    )
}
