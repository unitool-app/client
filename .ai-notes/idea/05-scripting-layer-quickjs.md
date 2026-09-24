# 05 — Скриптовый слой (QuickJS): донастройка поведения кирпичей кодом из документа

> **Версия заметки:** 1.0 · **Статус:** идея, отложена (критерии включения — §8) · **Дата:** 2026-09-22
>
> Читать после `04-review-and-bricks-architecture.md`: здесь описан один из видов коннектора
> (§6.2 там, строка `script`) и один необязательный порт. Ничего из этой заметки не входит в
> этапы 0–4.
>
> **История:** 1.0 — 2026-09-22, выделена из пятого уточнения владельца по его просьбе.

---

## 1. Что попросил владелец

«Можем строить JS-C, маленький компилятор Белларда, и использовать его, чтобы донастраивать
поведение форм и всего остального. Когда получаем конфиг от сервера — переопределяем то, как
кирпич будет общаться с сервером. Хотя JS-C впереди можно сделать. Это легче, чем внутри
приложения держать компилятор, пробрасывать и выводить. Добавь это отдельным описанием.»

Уточнение по транскрипции. «Маленький компилятор Белларда» — у Фабриса Беллара два кандидата:
**QuickJS** (встраиваемый JavaScript-движок, ~600 КБ, ES2023) и **TCC** (компилятор C). На
телефоне подходит только QuickJS: C-код, скомпилированный в рантайме, — исполняемый нативный код,
который Google Play запрещает загружать не из Play, а JavaScript в интерпретаторе без прямого
доступа к системным API — прямо разрешённое исключение той же политики («code that runs in a
virtual machine or an interpreter»). Дальше — QuickJS.

---

## 2. Что скрипт мог бы делать, и что — не должен

Три точки, в которых кусок кода из документа имел бы смысл, по убыванию ценности:

| Точка | Что | Пример | Без скрипта это… |
|---|---|---|---|
| **A. Коннектор `script`** | реализует порты кирпича (04 §5) поверх чужого API, который менять нельзя | `records.query = (c, q) => host.http.post("/v1/" + c + "/search", mapQuery(q)).then(mapRows)` | кодовый коннектор в сборке Unitool = релиз в Play на каждый новый бэкенд |
| **B. Порт `rules@1`** | валидация и вычисляемые поля формы; видимость полей; подстановки | `validate = doc => doc.qty > 0 || "Количество должно быть больше нуля"` | правила в конфиге кирпича на ограниченном языке (§6) или в коде кирпича |
| **C. Преобразование ответов** | нормализация формата чужого API под порт | частный случай A | — |

Чего скрипт **не делает** — граница, которую стоит зафиксировать заранее, потому что она
единственная защищает нативность форм:

- **Не рисует UI.** Никакого `render(tree)`. Это Tier 3 из заметки 10 — отдельный продукт, и
  владелец сказал прямо: «форма жёстко запрограммирована внутри приложения».
- **Не навигирует.** Выходы (04 §7.3) — язык сборки, не скрипта.
- **Не ходит в сеть сам.** Только `host.http` в пределах `hosts` коннектора (04 D115). Прямого
  `fetch`, сокетов, файловой системы нет.
- **Не читает чужое.** Хранилище — пространство коннектора; секрет — только как заголовок,
  который подставляет хост, значение скрипту не отдаётся.

---

## 3. Форма

### 3.1. Коннектор `script` в документе

```json
"legacy": { "kind": "script", "version": 1,
            "hosts": ["erp.example.com"],
            "auth": { "ref": "secret:erp" },
            "ports": ["records@1", "lookup@1"],
            "source": { "sha256": "…", "url": "https://api.dailyscope.example/scripts/erp-connector@7.js" },
            "settings": { "base": "https://erp.example.com/api" } }
```

Скрипт — **по ссылке с хэшем**, а не текстом в документе: документ кэшируется и показывается в
Service Menu, а скрипт может быть большим; хэш делает его частью версии документа. Кэш скрипта —
по хэшу, повторная загрузка — только при смене.

### 3.2. Что видит скрипт

```js
// scriptApi@1 — единственный глобальный объект
export const ports = {
  "records@1": {
    query:  async (collection, query) => { … },
    get:    async (collection, id) => { … },
    create: async (collection, record) => { … },
    update: async (collection, id, patch) => { … },
  },
  "lookup@1": { lookup: async (kind, code) => { … } },
};

// host.http.{get,post,patch}(path, body?, headers?) → {status, body, headers}
//   — путь относительный к settings.base; хост подставляет Authorization из auth.ref;
//     хост отвергает адрес вне hosts (04 D115); таймаут 15 с, без повторов (D32)
// host.storage.{get,set,remove}(key)  — пространство коннектора
// host.log.{debug,info,warn,error}(msg)
// host.now()                          — Clock (D30), чтобы тесты были детерминированы
// host.settings                       — JSON из документа
```

Сигнатуры портов — те же, что у Kotlin-интерфейсов 04 §5.1, потому что они изначально
JSON-дружественные. Это не совпадение, а причина того правила.

### 3.3. Порт `rules@1` (точка B)

```kotlin
public interface RulesPort {
    /** null — валидно; строка — сообщение человеку; объект — сообщения по полям. */
    public suspend fun validate(form: String, document: JsonObject): Result<JsonElement?>
    /** Возвращает вычисленные поля, которые кирпич подмешивает в документ до показа/отправки. */
    public suspend fun compute(form: String, document: JsonObject): Result<JsonObject>
    public companion object { public val Key: ServiceKey<RulesPort> = portKey("rules", 1) }
}
```

Кирпич объявляет `rules@1` **необязательным** (`runtime.service(key)`, не `require`): без
привязки правил форма работает на своей встроенной валидации. Коннектор `script` реализует его
как любой другой порт.

---

## 4. Движки по платформам

| Платформа | Вариант | Размер / цена | Заметка |
|---|---|---|---|
| **Android** | QuickJS через JNI-обёртку (`quickjs-kt`, `quickjs-android`) | ~0,6–1 МБ на ABI; один native-модуль | единственный вариант без WebView; WebView с `evaluateJavascript` — тяжелее и течёт по контексту |
| **Desktop (JVM)** | тот же QuickJS через JNI (`quickjs-kt` собирает под JVM) **или** Rhino (чистая Java) | QuickJS — общая семантика с Android; Rhino — ES5+, медленнее, зато без native | предпочтение — один движок на все таргеты |
| **wasmJs (браузер)** | **без движка**: JS уже есть. Изолированный `Worker`/`iframe` с `postMessage` | 0 | другая песочница → отдельный набор тестов на ограничения |
| **iOS** (когда будет) | QuickJS (JavaScriptCore есть в системе, но семантика другая) | как Android | — |

Итого: **два разных исполнителя** (QuickJS-в-процессе и браузерный Worker) и одна обёртка
`ScriptEngine` в `host` с `expect/actual`, как у `PushTransport`. Тесты скриптов — на JVM с
QuickJS, чтобы то, что прошло в тесте, так же работало на телефоне.

---

## 5. Песочница и ограничения

- **Время:** прерывание по `interrupt handler` QuickJS через 2 с на вызов порта; в Worker —
  `terminate()` по таймеру.
- **Память:** лимит кучи 16 МБ (`JS_SetMemoryLimit`); превышение — ошибка порта, не падение
  процесса.
- **Ввод-вывод:** только `host.*`. Нет `std`/`os` модулей QuickJS, нет `import` по сети, нет
  `eval` внешних строк (сам скрипт — единственный `eval`).
- **Секрет:** хост ставит заголовок; скрипт не видит значение. Скрипт, попросивший
  `host.http` на хост вне `hosts`, получает отказ и событие `ScriptViolation`.
- **Целостность:** `sha256` из документа проверяется до исполнения; несовпадение → коннектор
  `unsupported(script_hash)`, экраны на нём — `needs_update`. Подпись — D78, зарезервировано.
- **Наблюдаемость:** `host.log` — в кольцевой буфер Service Menu с тегом `script:<connector>`;
  необработанное исключение → `Result.failure` порта с текстом, кирпич показывает ошибку.
- **Play:** интерпретатор без прямого доступа к Android API, весь ввод-вывод — через хост с
  allowlist. Это разрешённое исключение политики «Device and Network Abuse»; формулировку
  политики приватности придётся дополнить («сервер может обновлять правила обмена данными»).

---

## 6. Альтернатива для точки B: выражения без движка

Для правил формы (валидация, видимость, вычисления) полноценный JS избыточен. Дешевле — язык
выражений без циклов и без ввода-вывода: **JSONLogic** (JSON-дерево `{"and":[…]}`, реализуется
в `commonMain` за пару сотен строк, детерминирован, тривиально валидируется) или CEL-подобные
строки. Правила едут внутри `screens[].config` — то есть **в языке кирпича**, и хост о них не
знает вообще. Кирпич `record-form` из 04 §15 (этап 3) может понимать JSONLogic с первого дня,
и точка B закрывается без скриптового слоя.

Рекомендация: **B — через выражения в конфиге кирпича; A — через `script`, когда наступят
критерии §8.** Смешивать не надо: правила формы, написанные на JS в коннекторе, привязали бы
кирпич к коннектору — обратно к И1 из 04.

---

## 7. Чего это стоит

| Статья | Оценка |
|---|---|
| `ScriptEngine` expect/actual (QuickJS JNI + Worker) | 2–3 дня, плюс сборка native под ABI в CI |
| `ScriptConnectorKind`: загрузка по хэшу, песочница, мост `host.*`, реализация портов через вызовы в движок | 2 дня |
| Тесты на ограничения (таймаут, память, хост вне списка, хэш) — на JVM и в браузере | 1 день |
| Документация `scriptApi@1` для тех, кто пишет коннекторы на сервере | 0,5 дня |
| Ревью безопасности и правка политики приватности / Data Safety | не оценивается здесь |
| Размер APK | +0,6–1 МБ на ABI |

Против этого — кодовый коннектор под один чужой API: 1–2 дня и релиз в Play. Скриптовый слой
окупается с **третьего** чужого бэкенда, не раньше.

---

## 8. Критерии включения

Заметка остаётся идеей, пока не выполнено хотя бы одно:

1. Появился **третий** бэкенд, который не может реализовать `superizer-http` (04 §5.3) и под
   который не хочется третий кодовый коннектор.
2. Появился заказчик, которому нужно **менять отображение полей на свой API без релиза** Unitool
   чаще, чем раз в месяц.
3. Порт `rules@1` понадобился с логикой, которую JSONLogic (§6) не выражает (обращение к
   справочнику во время валидации — то есть ввод-вывод).

До этого: `superizer-http` + кодовые коннекторы + выражения в конфиге кирпича. Этого хватает на
всё, что описано в 03 §1 как цели G1–G8.

---

## 9. Решения (продолжают нумерацию 04; там последнее — D120)

- **D121.** Скриптовый слой — это **вид коннектора** (`script`) и, отдельно, необязательный порт
  `rules@1`. Никакого UI и никакой навигации из скрипта.
- **D122.** Движок — QuickJS в процессе на Android/desktop/iOS, браузерный `Worker` в wasmJs;
  единая обёртка `ScriptEngine` expect/actual; тесты скриптов на JVM с QuickJS.
- **D123.** Скрипт доставляется по ссылке с `sha256`, кэшируется по хэшу; текста скрипта в
  документе нет.
- **D124.** Песочница: `host.*` — единственный ввод-вывод; хосты по allowlist коннектора; секрет
  подставляет хост; лимиты времени и памяти; нарушение — событие и `Result.failure`.
- **D125.** Правила формы (точка B) — сначала выражениями (JSONLogic) в конфиге кирпича, не JS.
- **D126.** Включается по критериям §8, не раньше этапа 5 из 04 §15.

## 10. Вопросы владельцу (продолжают нумерацию 04; там последний — 58)

59. **QuickJS или TCC** — подтвердить, что речь о JavaScript-движке (§1). Если имелось в виду
    что-то третье (например, Lua), модель та же, меняется движок.
60. **Есть ли уже бэкенд, который не сможет реализовать `superizer-http`?** Если да — критерий 1
    ближе, чем кажется, и это меняет порядок этапов в 04 §15.
61. **JSONLogic для правил формы** — устраивает ли как первый язык правил, или нужны строки
    вида `qty > 0 && warehouse != ""` (CEL-подобные)? По умолчанию — JSONLogic: он уже JSON, и
    админка может собирать его конструктором.


Да. Я посмотрел QuickJS и `quickjs-kt` именно с точки зрения такой архитектуры. Главный вывод: **сложность здесь не в ABI JS↔Kotlin**. Его в обычном смысле вообще не должно быть. Надёжная схема — иметь ABI только между Kotlin и нативным QuickJS, а между Kotlin и загружаемым JS сделать свой версионируемый RPC/contract layer.

### Как это реально будет сшито

Архитектурно я бы сделал так:

```text
                    server
                      │
          ┌───────────┴───────────┐
          │                       │
      manifest.json          controller.js
          │                       │
          └───────────┬───────────┘
                      ▼
              Kotlin ControllerHost
                      │
             contract validation
                      │
                      ▼
               QuickJS runtime
                      │
               bootstrap.js
              (из приложения)
                      │
             controller.js
              (из сервера)
                      │
           async dispatch(json)
                      │
                      ▼
               result JSON
                      │
                      ▼
             Kotlin serialization
                      │
                      ▼
                Compose State
```

И вот это принципиально: не пытаться заставлять JS «реализовывать Kotlin `abstract class`» физически.

В Kotlin у тебя может существовать:

```kotlin
interface ScreenController {
    suspend fun dispatch(event: UiEvent): ControllerResult
    fun close()
}
```

Но реализация всего одна:

```kotlin
class QuickJsScreenController(
    private val runtime: JsRuntime
) : ScreenController
```

А JS-класс уже реализует **логический** контракт.

Например:

```javascript
export default class OrderController {
    static apiVersion = 3;

    async dispatch(event) {
        switch (event.type) {
            case "INIT":
                return this.init(event.payload);

            case "FIELD_CHANGED":
                return this.fieldChanged(event.payload);

            case "SUBMIT":
                return this.submit(event.payload);

            default:
                throw new Error(
                    `Unsupported event: ${event.type}`
                );
        }
    }
}
```

---

## Где здесь ABI

Получается три разных уровня:

```text
Kotlin
  │
  │ Kotlin/JNI или Kotlin/Native CInterop
  ▼
QuickJS C API
  │
  │ JSValue / JSContext / JSRuntime
  ▼
QuickJS
  │
  │ НАШ protocol
  ▼
controller.js
```

Первый уровень — настоящий native ABI.

На Kotlin/Native `cinterop` читает C headers и генерирует Kotlin bindings для C API. Именно для таких задач он и предназначен. ([Kotlin][1])

У QuickJS API достаточно небольшой:

```c
JS_NewRuntime()
JS_NewContext()

JS_Eval()

JS_GetGlobalObject()
JS_GetPropertyStr()

JS_Call()

JS_NewString()

JS_GetException()

JS_FreeValue()
```

Сам QuickJS построен вокруг `JSRuntime`, `JSContext` и `JSValue`; `JSValue` reference-counted, поэтому если идти напрямую через C API, нужно внимательно управлять `JS_DupValue`/`JS_FreeValue`. ([QuickJS NG][2])

Но `quickjs-kt` большую часть этого уже прячет.

---

# А JS↔Kotlin ABI я бы вообще убрал

Например, Kotlin посылает JS:

```json
{
  "protocol": 3,
  "requestId": "abc123",
  "type": "FIELD_CHANGED",
  "payload": {
    "field": "city",
    "value": "Nha Trang"
  }
}
```

А JS обязан вернуть:

```json
{
  "protocol": 3,
  "requestId": "abc123",
  "actions": [
    {
      "type": "SET_VALUE",
      "field": "deliveryPrice",
      "value": 40000
    }
  ]
}
```

Получается один метод:

```text
dispatch(JSON) → JSON
```

И всё.

Это очень сильно облегчает совместимость.

---

# Как определить, что «абстрактный класс» реализован правильно

Вот здесь я бы сделал проверку в несколько слоёв.

При загрузке приложения Kotlin сначала **не показывает UI**, а запускает handshake.

Например manifest:

```json
{
  "id": "restaurant.order",
  "runtime": "js",
  "controllerApi": 3,
  "entry": "controller.js",

  "requires": [
    "http.v2",
    "storage.v1",
    "navigation.v1"
  ]
}
```

После загрузки `bootstrap.js` проверяет модуль:

```javascript
const controller = new Module.default();

if (typeof controller.dispatch !== "function") {
    throw new ContractError(
        "Controller must implement dispatch(event)"
    );
}
```

Потом:

```javascript
if (Module.default.apiVersion !== 3) {
    throw new ContractError(
        "Unsupported Controller API"
    );
}
```

То есть ошибка возникает **до открытия экрана**.

---

## Ещё лучше — писать контроллеры на TypeScript

Это, на мой взгляд, оптимальный вариант.

Разработчик видит:

```typescript
export interface ScreenController {
    dispatch(
        event: UiEvent
    ): Promise<ControllerResult>;
}
```

И:

```typescript
export class OrderController
    implements ScreenController {

    async dispatch(
        event: UiEvent
    ): Promise<ControllerResult> {
        // ...
    }
}
```

На сервере:

```text
TypeScript
    ↓
type checking
    ↓
JavaScript
    ↓
server
    ↓
mobile QuickJS
```

То есть у нас две проверки:

```text
build time:
TypeScript interface validation

runtime:
QuickJS contract validation
```

И потом ещё третья:

```text
Kotlin:
kotlinx.serialization
```

Это существенно надёжнее, чем просто надеяться на dynamic typing.

---

# Что делать с типами

`quickjs-kt` уже умеет автоматически конвертировать довольно много:

```text
JS              Kotlin

null            null
boolean         Boolean
number          Int/Long/Double/...
string          String
Array           List
Set             Set
Map             Map
object          JsObject
Int8Array       ByteArray
```

Есть также custom converters и отдельная интеграция с `kotlinx.serialization`. ([GitHub][3])

Но для вашего случая я всё равно предпочёл бы:

```text
JS object
   ↓
JSON
   ↓
@Serializable Kotlin model
```

Например:

```kotlin
@Serializable
sealed interface ControllerAction

@Serializable
data class SetValue(
    val field: String,
    val value: JsonElement
) : ControllerAction
```

Потому что тогда нельзя получить полусломанный объект и узнать об этом через пять функций.

Получили ответ → deserialize → либо валидный `ControllerResult`, либо:

```text
ControllerProtocolException
```

---

# Как Kotlin будет вызывать JS

Вот здесь есть небольшой подводный камень конкретно у `quickjs-kt`.

Библиотека хорошо умеет:

```kotlin
quickJs.evaluate(...)
```

и:

```kotlin
function("foo") {
    // JS -> Kotlin
}
```

и даже:

```kotlin
asyncFunction("request") {
    // Kotlin suspend function
}
```

`asyncFunction` превращается для JS в Promise-модель и интегрируется с coroutines. ([GitHub][3])

Но удобное API вида:

```kotlin
controller.call("dispatch", event)
```

для долгоживущего JS-объекта пока не выглядит полностью завершённым публичным API — в репозитории есть открытая задача именно про вызов JS-функций из Kotlin. ([GitHub][4])

Это не блокер.

Для первой реализации можно делать:

```kotlin
quickJs.evaluate<String>(
    """
    await globalThis.__runtime.dispatch($event)
    """
)
```

То есть при загрузке:

```javascript
globalThis.__runtime = {
    controller: new Controller(),

    async dispatch(event) {
        return JSON.stringify(
            await this.controller.dispatch(event)
        );
    }
};
```

А Kotlin вызывает его.

---

# А для production я бы добавил маленький native call

То есть форкнул/расширил `quickjs-kt` методом условно:

```kotlin
suspend fun call(
    objectName: String,
    functionName: String,
    arguments: List<Any?>
): Any?
```

Внутри это фактически:

```text
JS_GetGlobalObject()
        ↓
JS_GetPropertyStr("__runtime")
        ↓
JS_GetPropertyStr("dispatch")
        ↓
JS_Call()
        ↓
JSValue
```

Вот это уже настоящий вызов без повторного parsing JS-кода.

QuickJS изначально предоставляет `JS_Call` и C API именно для такого embedding. ([QuickJS NG][2])

---

# JS → Kotlin ещё проще

При создании runtime регистрируем ограниченный Host API:

```javascript
host.request(...)
host.storageGet(...)
host.storageSet(...)
host.navigate(...)
host.log(...)
```

В Kotlin:

```kotlin
quickJs {
    define("host") {

        asyncFunction("request") { args ->
            httpClient.request(...)
        }

        function("log") { args ->
            logger.log(...)
        }
    }
}
```

`quickjs-kt` прямо поддерживает обычные и async Kotlin bindings. ([GitHub][3])

Поэтому получается:

```javascript
const response = await host.request({
    method: "POST",
    path: "/order/recalculate",
    body: state
});
```

JS вообще не знает, что под ним Android/iOS.

---

# Что будет при ошибке JS

Это сделано достаточно удобно.

Например JS:

```javascript
function calculate() {
    throw new Error("Invalid product");
}
```

`quickjs-kt` преобразует это в:

```kotlin
QuickJsException
```

с доступными:

```text
fileName
lineNumber
columnNumber
stack
```

([GitHub][3])

Причём свежий релиз `quickjs-kt` 1.0.14 от 15 августа 2026 как раз содержит исправление propagation исключений из bindings. ([GitHub][5])

Я бы поверх этого ввёл свою ошибку:

```kotlin
data class ControllerError(
    val moduleId: String,
    val moduleVersion: String,
    val event: String,

    val type: ErrorType,

    val message: String,
    val file: String?,
    val line: Int?,
    val column: Int?,
    val stack: String?
)
```

И такие ошибки можно отправлять на сервер.

Тогда вы реально увидите:

```text
restaurant.order@1.34.2

FIELD_CHANGED
field = pizzaSize

controller.js:143:18

TypeError:
Cannot read property 'price' of undefined
```

Это уже вполне нормальная диагностика production runtime.

---

# А если JS завис

Например сервер случайно прислал:

```javascript
while (true) {}
```

QuickJS имеет interrupt handler:

```c
JS_SetInterruptHandler()
```

а также:

```c
JS_SetMemoryLimit()
JS_SetMaxStackSize()
```

То есть можно ограничивать CPU, heap и stack. ([Bellard][6])

`quickjs-kt` уже делает удобную Kotlin-обёртку:

```kotlin
quickJs.evaluationTimeoutMillis = 1000
```

и поддерживает:

```kotlin
withTimeout(...) { ... }
```

и:

```kotlin
quickJs.interruptEvaluation()
```

([GitHub][3])

Например:

```text
dispatch()
maximum: 500 ms

memory:
32 MB

stack:
1 MB
```

Если контроллер нарушил лимит — прибиваем конкретный runtime и показываем fallback UI.

---

# Очень важное ограничение QuickJS

Один `JSRuntime` **не является multithreaded**. Разные runtimes могут существовать одновременно, но объекты между ними не передаются. Несколько contexts внутри одного runtime могут разделять объекты, но сам runtime надо использовать последовательно. ([QuickJS NG][2])

Поэтому я бы делал:

```text
Screen A
   ↓
QuickJS runtime A

Screen B
   ↓
QuickJS runtime B
```

но не для тысячи приложений одновременно.

Реально:

```text
открыли micro-app
       ↓
создали runtime
       ↓
load controller
       ↓
работаем
       ↓
закрыли экран
       ↓
runtime.close()
```

Можно потом добавить небольшой pool.

---

# Не гонять через JS всё подряд

Это тоже важно.

Не надо:

```text
finger move
↓
JS
↓
Kotlin
↓
Compose
```

120 раз в секунду.

Или:

```text
every layout pass → JS
```

Compose должен самостоятельно заниматься:

```text
layout
animation
scroll
gesture
focus
text rendering
```

А JS только:

```text
onClick
onSubmit
onChange
onOpen
onLoad
business rules
server calls
navigation decisions
```

Например пользователь вводит:

```text
И
Ив
Ива
Иван
```

Необязательно делать четыре round-trip.

Можно debounce:

```text
Compose TextField
       ↓
local state
       ↓ 150 ms
FIELD_CHANGED
       ↓
QuickJS
```

---

# Как решать совместимость версий

Я бы сделал настоящий version negotiation:

```json
{
    "runtimeProtocol": 4,

    "controller": {
        "minProtocol": 3,
        "maxProtocol": 5
    },

    "requiredCapabilities": {
        "http": 2,
        "navigation": 1,
        "storage": 1,
        "ui": 7
    }
}
```

Клиент знает:

```text
protocol: 4

capabilities:
http = 2
navigation = 2
storage = 1
ui = 9
```

И проверяет:

```text
controller supports protocol 4? YES

http >= 2? YES
navigation >= 1? YES
storage >= 1? YES
ui >= 7? YES
```

После этого только запускает JS.

Это и есть фактически **наш ABI**, только не binary ABI, а application protocol ABI.

И он намного безопаснее.

---

# Что я бы НЕ делал

Единственный список, который здесь действительно важен:

* не передавал бы Kotlin objects напрямую и не держал бы `JSValue` в бизнес-коде;
* не пытался бы отражать Kotlin `abstract class` один-в-один в JS;
* не давал бы JS arbitrary native API;
* не отправлял бы с сервера заранее скомпилированный QuickJS bytecode как основной формат;
* не запускал бы всю UI-логику через JS;
* не делал бы один глобальный QuickJS runtime на всё приложение.

Особенно насчёт bytecode: QuickJS прямо предупреждает, что его bytecode **привязан к конкретной версии QuickJS**, и бинарный bytecode не имеет security validation при загрузке. ([Bellard][6])

Поэтому серверу лучше отдавать:

```text
controller.js
```

а клиент при необходимости сам делает:

```text
JS source
   ↓
QuickJS compile
   ↓
local bytecode cache
```

`quickjs-kt` уже имеет compile + bytecode caching, при этом документация отдельно предупреждает, что cache зависит от версии engine. ([GitHub][3])

---

# В итоге сложность

**Сам QuickJS встроить в KMP сейчас не выглядит тяжёлой частью.** `quickjs-kt` уже поддерживает Android, JVM и Kotlin/Native, bindings, coroutines, modules, bytecode cache, interruption и type conversion. ([GitHub][3])

Основная работа будет не в QuickJS, а в создании хорошего:

```text
              Controller SDK
                    │
     ┌──────────────┼───────────────┐
     ▼              ▼               ▼
TypeScript      protocol        capabilities
definitions      schema            schema
     │              │               │
     └──────────────┼───────────────┘
                    ▼
                bootstrap
                    │
                    ▼
              QuickJS adapter
                    │
                    ▼
               Kotlin State
                    │
                    ▼
                  UI
```

И я бы остановился примерно на **5 функциях уровня runtime**:

```text
load(module)

validate()

dispatch(event)

hostCall(request)

dispose()
```

Всё остальное идёт JSON-сообщениями.

Для вашей идеи микроприложений это, на мой взгляд, гораздо более жизнеспособно, чем пытаться динамически загружать Kotlin/Native/Kotlin JVM код: **Compose остаётся нативным и типизированным, а изменяемая часть превращается в маленькие sandboxed JS-контроллеры с чётким protocol ABI.**

[1]: https://kotlinlang.org/docs/native-c-interop.html?utm_source=chatgpt.com "Interoperability with C | Kotlin Documentation"
[2]: https://quickjs-ng.github.io/quickjs/developer-guide/intro/?utm_source=chatgpt.com "The QuickJS C API | QuickJS-NG"
[3]: https://github.com/dokar3/quickjs-kt "GitHub - dokar3/quickjs-kt: A QuickJS binding for idiomatic Kotlin, with Async/DSL/ES Modules support. · GitHub"
[4]: https://github.com/dokar3/quickjs-kt/issues "Issues · dokar3/quickjs-kt · GitHub"
[5]: https://github.com/dokar3/quickjs-kt/releases?utm_source=chatgpt.com "Releases · dokar3/quickjs-kt · GitHub"
[6]: https://bellard.org/quickjs/quickjs.html?utm_source=chatgpt.com "QuickJS Javascript Engine"
