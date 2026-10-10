# 00 — бриф владельца (как прислан, 2026-10-06)

> Владелец прислал этот текст вместе с просьбой: «расследуй, насколько реально сделать такой
> мини-апп для нашего приложения. SSH-ключи уже есть, надо как-то сделать подписание на Android
> и iOS. Пока только план». Ниже — бриф без изменений, ссылки вида «бриф §10» ведут сюда.

# iPhone Hardware SSH Key

## Цель

Сделать open-source решение, позволяющее использовать iPhone как аппаратный ключ для SSH.

Приватный ключ должен генерироваться и храниться в Secure Enclave iPhone и никогда не покидать устройство.

При выполнении:

```bash
ssh user@server
```

OpenSSH на компьютере должен обращаться к локальному `ssh-agent`, агент передавать запрос подписи на подключенный iPhone, iPhone показывать запрос пользователю, подтверждать операцию через Face ID / Touch ID / passcode и возвращать готовую подпись.

На SSH-сервер устанавливается только обычный публичный SSH-ключ. Никакого специального ПО на SSH-сервере быть не должно.

---

# 1. Целевая архитектура

```text
┌──────────────────────────────┐
│        SSH server            │
│                              │
│ ~/.ssh/authorized_keys       │
│ ecdsa-sha2-nistp256 ...      │
└──────────────▲───────────────┘
               │ SSH
               │
┌──────────────┴───────────────┐
│       Desktop / Laptop       │
│                              │
│ OpenSSH ssh                  │
│       │                      │
│       ▼                      │
│ SSH_AUTH_SOCK                │
│       │                      │
│       ▼                      │
│ iphone-ssh-agent             │
│       │                      │
│       │ USB transport        │
└───────┼──────────────────────┘
        │
        │ USB
        │
┌───────▼──────────────────────┐
│           iPhone             │
│                              │
│ iOS companion application    │
│       │                      │
│       ▼                      │
│ User confirmation            │
│ Face ID / Touch ID           │
│       │                      │
│       ▼                      │
│ Secure Enclave               │
│ P-256 private key            │
│       │                      │
│       ▼                      │
│ ECDSA signature              │
└──────────────────────────────┘
```

Основной принцип:

**компьютер никогда не получает приватный ключ.**

Он получает только:

- public key;
- key metadata;
- результат операции `sign(data)`.

---

# 2. MVP

Первый MVP должен поддерживать:

- iPhone;
- macOS host;
- OpenSSH;
- стандартный SSH Agent Protocol;
- один P-256 ключ;
- Secure Enclave;
- Face ID перед каждой SSH-подписью;
- USB-соединение;
- pairing одного Mac с одним iPhone;
- получение OpenSSH public key;
- обычный SSH login без изменений SSH-сервера.

После успешного MVP добавить Linux.

Windows оставить следующим этапом.

---

# 3. iOS-приложение

Написать приложение на Swift.

Использовать:

- CryptoKit;
- SecureEnclave.P256.Signing.PrivateKey;
- LocalAuthentication;
- Keychain / SecAccessControl.

## Генерация ключа

Приложение должно уметь создать:

```text
Secure Enclave
└── P-256 Signing Private Key
```

Private key не должен экспортироваться.

Необходимо сохранить persistent representation ключа так, чтобы приложение могло восстановить ссылку на него после перезапуска.

## Public key

Получить P-256 public key и преобразовать его в OpenSSH representation:

```text
ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTIt...
```

Пользователь должен иметь кнопку:

```text
Copy SSH Public Key
```

для помещения ключа в:

```text
~/.ssh/authorized_keys
```

---

# 4. Подписание

Desktop должен иметь возможность отправить iPhone запрос:

```text
SIGN_REQUEST

key_id
data
request_id
host information
```

iPhone показывает UI:

```text
SSH authentication

Host:
server.example.com

User:
deploy

Requested by:
MacBook Pro

[ Reject ]       [ Approve ]
```

После Approve:

```text
Face ID
    ↓
Secure Enclave
    ↓
ECDSA P-256 signature
```

Возвращается:

```text
SIGN_RESPONSE

request_id
signature
```

Private key никогда не передается.

---

# 5. SSH Agent

На desktop реализовать стандартный `ssh-agent` compatible Unix socket.

Например:

```bash
export SSH_AUTH_SOCK="$HOME/.iphone-ssh-agent/socket"
```

После этого стандартные:

```bash
ssh-add -L
ssh user@server
git clone git@github.com:org/repo.git
scp ...
rsync -e ssh ...
```

должны работать без модификации OpenSSH.

Минимально реализовать необходимые сообщения SSH Agent Protocol:

```text
SSH_AGENTC_REQUEST_IDENTITIES
SSH_AGENT_IDENTITIES_ANSWER

SSH_AGENTC_SIGN_REQUEST
SSH_AGENT_SIGN_RESPONSE

SSH_AGENT_FAILURE
```

На `REQUEST_IDENTITIES` агент возвращает public key с iPhone.

На `SIGN_REQUEST` агент передает payload телефону и ожидает подпись.

---

# 6. Формат SSH-подписи

Особое внимание уделить преобразованию:

```text
Secure Enclave / CryptoKit
ECDSA signature
```

в:

```text
SSH ecdsa-sha2-nistp256 signature blob
```

Нужно корректно обработать:

```text
r
s
```

ECDSA-подписи и SSH `mpint` encoding.

Результат должен быть полностью совместим со стандартным OpenSSH.

Никаких изменений `sshd` делать нельзя.

---

# 7. USB transport

Это главный исследовательский блок проекта.

Необходимо найти наиболее надежный способ организовать:

```text
macOS/Linux
      ↕
     USB
      ↕
iOS application
```

Требования:

- без jailbreak;
- App Store-compatible — желательно, но для MVP допускается development/sideload build;
- mutual pairing;
- authentication desktop ↔ iPhone;
- framing сообщений;
- reconnect;
- timeout;
- защита от replay.

Исследовать использование существующей инфраструктуры Apple device communication и проектов семейства `libimobiledevice`.

Не создавать собственную криптографию поверх транспорта без необходимости.

---

# 8. Transport abstraction

USB не должен быть жестко связан с SSH-agent.

Сразу определить интерфейс примерно:

```text
DeviceTransport

connect()
disconnect()

listKeys()

sign(
    keyID,
    payload,
    context
)
```

Тогда позже можно добавить:

```text
USBTransport
LocalNetworkTransport
BluetoothTransport
```

не меняя SSH-agent и Secure Enclave код.

---

# 9. Pairing

При первом соединении:

```text
Desktop
   ↕
iPhone
```

провести pairing.

iPhone должен показать:

```text
Allow this computer to request SSH signatures?

MacBook Pro
Fingerprint:
SHA256:....

[Reject] [Pair]
```

После pairing сохранить идентификатор desktop.

Неизвестный desktop не должен иметь возможности отправлять запросы подписи.

---

# 10. Security requirements

Обязательные свойства:

1. Private key никогда не покидает Secure Enclave.

2. Desktop compromise не должен позволять извлечь private key.

3. Каждая подпись по умолчанию требует user presence / Face ID.

4. iPhone показывает информацию о запросе до подтверждения.

5. Pairing desktop ↔ iPhone аутентифицирован.

6. Добавить request ID / nonce для защиты от replay.

7. После блокировки телефона операции подписи должны быть запрещены.

8. Не должно быть API:

```text
exportPrivateKey()
```

ни в каком виде.

---

# 11. Важное ограничение SSH

Отдельно исследовать, какую информацию реально можно надежно показать пользователю.

SSH-agent получает signing payload от OpenSSH, но это не означает, что из него всегда безопасно и однозначно можно получить красивое:

```text
user@hostname
```

Поэтому UI не должен утверждать, что показывает криптографически подтвержденный hostname, если desktop передает его только как metadata.

Разделять:

```text
SIGNED DATA
```

и:

```text
UNTRUSTED DISPLAY METADATA
```

Это важно для security model.

---

# 12. Desktop CLI

Желаемый интерфейс:

```bash
iphone-ssh-agent pair
iphone-ssh-agent devices
iphone-ssh-agent keys
iphone-ssh-agent public-key
iphone-ssh-agent run
```

Например:

```bash
$ iphone-ssh-agent devices

iPhone 17 Pro
Status: connected
Transport: USB
Paired: yes
```

и:

```bash
$ iphone-ssh-agent public-key

ecdsa-sha2-nistp256 AAAAE2VjZHNh...
```

---

# 13. OpenSSH config

Желательно обеспечить работу через:

```text
Host *
    IdentityAgent ~/.iphone-ssh-agent/socket
```

После этого:

```bash
ssh server
```

не требует дополнительных параметров.

---

# 14. Этапы разработки

### Phase 1 — Crypto PoC

iOS:

```text
generate Secure Enclave P-256 key
        ↓
export public key
        ↓
sign arbitrary bytes
        ↓
verify signature
```

Критерий готовности:

private key не экспортируется, а подпись успешно проверяется.

### Phase 2 — SSH signature PoC

Без USB.

Передать произвольный OpenSSH signing request iOS-коду и получить корректную SSH-compatible ECDSA signature.

Критерий:

OpenSSH принимает подпись.

### Phase 3 — SSH agent

Реализовать:

```text
SSH_AUTH_SOCK
```

и минимум:

```text
REQUEST_IDENTITIES
SIGN_REQUEST
```

Критерий:

```bash
ssh-add -L
```

видит iPhone key.

### Phase 4 — USB PoC

Реализовать:

```text
desktop → USB → iPhone
```

для простого:

```text
PING
PONG
```

После этого:

```text
LIST_KEYS
SIGN
```

### Phase 5 — End-to-end

Добиться:

```bash
$ ssh user@test-server
```

→ notification/UI на iPhone

→ Face ID

→ Secure Enclave signature

→ успешный SSH login.

Это является основным milestone MVP.

### Phase 6 — Hardening

Добавить:

- pairing;
- authenticated transport;
- replay protection;
- reconnect;
- timeout;
- request cancellation;
- multiple keys;
- audit/logging без сохранения sensitive payloads.

### Phase 7 — Linux

Портировать desktop agent.

Предпочтительно разделить:

```text
SSH Agent Core
Transport
Platform integration
```

чтобы большая часть кода была общей.

---

# 15. Референсы

## Akamai AKR

GitHub:

https://github.com/akamai/akr

Изучить в первую очередь.

Это наиболее близкая архитектурная реализация:

```text
OpenSSH
  ↓
SSH agent
  ↓
remote mobile authenticator
  ↓
signature
```

Посмотреть:

- SSH-agent implementation;
- pairing;
- request/response;
- mobile approval UX;
- обработку SSH signatures.

Не копировать архитектуру вслепую — использовать как reference.

---

## sod

GitHub:

https://github.com/botanica-consulting/sod

Референс для:

```text
OpenSSH
   ↓
SSH Agent
   ↓
Secure Enclave
```

Особенно изучить:

- SSH Agent Protocol;
- ECDSA P-256;
- OpenSSH encoding;
- Secure Enclave signing;
- authentication before signing.

---

## sshenc

GitHub:

https://github.com/godaddy/sshenc

Дополнительный reference реализации SSH key через Secure Enclave.

Изучить взаимодействие с:

```text
IdentityAgent
SSH_AUTH_SOCK
OpenSSH
```

---

## AgePony / AgePonyiOS

GitHub:

https://github.com/norsehorse-dev/AgePonyiOS

Полезный Swift/iOS reference.

Посмотреть:

- Secure Enclave;
- CryptoKit;
- P-256;
- SSH-compatible representations;
- FIDO/CTAP-related код;
- reusable Swift crypto components.

---

## libimobiledevice

GitHub:

https://github.com/libimobiledevice/libimobiledevice

Основной reference для исследования USB-коммуникации с iPhone.

Также посмотреть связанные проекты:

```text
usbmuxd
libusbmuxd
libimobiledevice
```

Нужно понять, можно ли построить поверх существующего Apple USB multiplexing собственный канал desktop ↔ iOS application без private API.

---

## OpenSSH

Исходники:

https://github.com/openssh/openssh-portable

Изучить:

```text
PROTOCOL.agent
ssh-agent.c
sshkey.c
ssh-ecdsa.c
```

OpenSSH является source of truth относительно формата SSH Agent Protocol и signature encoding.

---

## Apple CryptoKit / Secure Enclave

Изучить API:

```text
CryptoKit
SecureEnclave.P256.Signing.PrivateKey
P256.Signing.PublicKey
P256.Signing.ECDSASignature
```

Также:

```text
LocalAuthentication
SecAccessControl
Keychain Services
```

Цель — добиться hardware-backed ключа с обязательным подтверждением пользователя.

---

# 16. Что не делать

Не писать собственный SSH-клиент.

Не писать собственный SSH-сервер.

Не изменять `sshd`.

Не передавать private key компьютеру.

Не хранить копию private key в iCloud.

Не делать первоначально сложный GUI для desktop.

Не начинать с Windows.

Не начинать с Bluetooth.

Не пытаться сразу реализовать FIDO2/CTAP2, если обычный SSH-agent значительно проще.

Первый end-to-end должен быть максимально узким:

```text
macOS
 +
OpenSSH
 +
SSH_AUTH_SOCK
 +
USB
 +
iPhone
 +
Face ID
 +
Secure Enclave P-256
```

---

# 17. Definition of Done MVP

Проект считается имеющим работающий MVP, когда на чистом Mac можно выполнить:

```bash
$ ssh-add -L
ecdsa-sha2-nistp256 AAAA... iphone-secure-enclave
```

затем:

```bash
$ ssh user@server
```

На iPhone появляется запрос.

Пользователь подтверждает Face ID.

Secure Enclave подписывает SSH authentication request.

OpenSSH получает signature.

Обычный OpenSSH `sshd` принимает ее.

Пользователь входит на сервер.

При отключенном iPhone:

```bash
ssh user@server
```

не может воспользоваться этим ключом.

При копировании файлов desktop agent на другой компьютер private key восстановить невозможно.

Это и есть минимальная реализация **iPhone as a hardware-backed SSH key**.
