package cx.m42.apps.twofa

/** Public keys made by `ssh-keygen` for these tests; their private halves were thrown away. */
internal object Fixtures {
    const val ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS fixture"
    const val ECDSA =
        "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo="
    const val RSA_1024 =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQDgKxSUwKWChU2E96mssCKyMEYyS284Oa/y2kjtrftPr4kX7otvUC4hIStxXSZVOu9z9xr79P4aTPPbn6mox6Zcri8c/3p1E2RXFPVncCorbTfW6Fr0b5JpiABHQg/fE000lF0516oycRWw/kKQQXyyoTVI0JMknBkGyN5yC3TrMw=="
    const val RSA_3072 =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDF9f3LvEHB12a4AXHr0ZCeEBSJaK3MNbkcwnETRnaaOa35+LCIzEEtTxieW2gBAv84/UwqEIt/fuwF53f5xuvmUJsFCZvP4A/dHIOjwa8Fz7jGqaJAed6zD+7zPM/B7AyxsYKVFXQpdxqz8Pr/PoZ6yrC4cKQSqZsHSaisapKo5Z8Kq8Yxqgzli9ebKHMagjl/rhonjul5+pH2kJz1Dwt+JeJJ0C6/6tjz32N0BAwgfis792s+pY0dqBPOv0C2INmQXBtaWdV4OJmeH18LBuXdR4HZ4txjpnb+QrTGRO6xzuYB4Rx/va58dKYlB3+jCb7Ag0fXt/FUSmf3+crxJ0ARRTI7PLvCEYFsxRvsFFuBvGqx8R2i+CT6fOktLJEg1uoX0UGalIemP+8hx2TcRgnBfW9Hi7jAqEuO3OO48dtRMqQ2UD38751+AKqEYM5pvBK90nR7b9+ctv/UdB2ZfuNraZNcnFd9D835Y3O9dRZHzS2zgwFy3yrgxKsosWX8OOE="
}
