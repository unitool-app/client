package cx.m42.unitool.kit.ssh

/**
 * age's own recipients, made once by the real `age-keygen` and `age` 1.1.1 on 2026-10-27 (cr-restore
 * D618): an identity file, a file to its `age1…` in both forms, and an `age -p` file (passphrase
 * [PASSPHRASE], work factor 18). Test keys, never used anywhere. `desktopTest` does the same live
 * (`AgeKeysInteropTest`).
 */
internal object AgeKeysFixtures {
    val IDENTITY_FILE: String = """# created: 2026-10-27T13:58:42+07:00
# public key: age1kevslv52l44lug6cqypeplh6j4trvj5822f9tk92cure2kvzc42qgm2upr
AGE-SECRET-KEY-1LW8S4Q6FXX9RRAKWFPRPUSNGRMZREL5Z7QKGEKQ69R929FTAU4CQVXQW0A
"""
    const val RECIPIENT: String = "age1kevslv52l44lug6cqypeplh6j4trvj5822f9tk92cure2kvzc42qgm2upr"
    const val TO_X25519: String = "YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSBFblpOSkE1c3ZwTW5mcnE0TjhDb2ZTMEMzRURtVUR2eEp6NU9uZEtweW1rCi9DZHhlcHgyZWJLRFh6VW9QMk4vdlZvMDdpNEtXZmVVZDh5RGRpN1h5MkEKLS0tIHRJLy9HYnlhdldobVdFZUx5OEhiblVMcTMzNHNISmM4MUwyeDkxa1Y1MUkKn7ZR7NskztgRxx1UJbwMwDBMasNiZVl3/vS7rYBkVFoax6lHb8dzTKfUNBSbnVBdd6/Fm9o="
    val TO_X25519_ARMORED: String = """-----BEGIN AGE ENCRYPTED FILE-----
YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSBiZ3ZUaGNvTGFET01HMUg3
MmV2NW9XR0ZDWis3Q2pCSkxwVkdSbVVoSkh3Cnh6NGRsUHpERnc4YlowUGFhQTc1
L0VkU2gvYm14a1NVT1ViRmlTTHdtczQKLS0tIGdUcWo3KzMzSjB5Tjg2bFQyMGRO
elA0NDhBWC96bjd6SEpWMTZEUmw1eDQKFmkLAkiu7nVtRhMooTZ51z+GmwpjdBBm
iWhE+QCMsXVIAReVdujf1OzrS2iROGhu4aFoCcE=
-----END AGE ENCRYPTED FILE-----
"""
    const val PASSPHRASE: String = "pw-тест"
    const val WITH_PASSPHRASE: String = "YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IHNjcnlwdCBqdmZCcWpCT3Y2QjNDSEhmRmhpZVFRIDE4CndBZ1UzUWovRWY3VXlIZGw3eHFReTRHd01XUE00TFZqcXptUGExU3VpUWcKLS0tIDRBbG5LcW5jUmcvZlpQRkp0a05vQ2lacW1rVUZQUGZKYXBOUndyYXpiYncKdJhLmPZl87ZcQZIgu3OoD7fDxYMSvV3R+WMtvnMSCs2pVdyXXg3znMScV/cuhW7zVZkkr70="
    const val PLAINTEXT: String = "0KHQtdC60YDQtdGCIGFnZSDinJMK"
}
