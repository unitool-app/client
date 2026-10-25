package cx.m42.apps.passwords

/** The words of "Computers" and of the request sheet (.ai-notes/passwords-browser README §5). */
internal interface BrowserStrings {
    val computers: String
    val computersTitle: String
    val computersIntro: String
    val noComputers: String
    val pairComputer: String
    val pairHint: String
    val noCamera: String
    fun pairedOn(date: String): String
    fun lastRequest(date: String, site: String): String
    val noRequestsYet: String
    val journal: String
    fun outcome(outcome: Outcome): String
    val forget: String
    fun confirmForget(name: String): String
    fun listening(port: Int): String
    val notListening: String
    val listenFailed: String

    fun pairQuestion(name: String): String
    val pairFingerprint: String
    val pairExplain: String
    val pairYes: String
    val pairWaiting: String
    val pairWaitingHint: String
    fun paired(name: String): String
    fun pairProblem(problem: BrowserLink.PairProblem): String
    val close: String
    val cancel: String

    val requestTitle: String
    fun requestFrom(computer: String, browser: String): String
    val computerSays: String
    val asciiForm: String
    val chooseEntry: String
    val noEntriesForSite: String
    val searchAll: String
    val showAll: String
    fun mismatch(entrySite: String, requested: String): String
    val send: String
    val deny: String
    fun secondsLeft(seconds: Long): String
    fun confirmSend(site: String, computer: String): String
}

internal object BrowserStringsRu : BrowserStrings {
    override val computers = "Компьютеры"
    override val computersTitle = "Компьютеры"
    override val computersIntro = "Привязанный компьютер может попросить пароль для сайта, открытого в браузере. Каждый раз телефон показывает запрос, вы выбираете запись и подтверждаете. Пароль шифруется ключом SSH компьютера и уходит только по вашей Wi-Fi-сети."
    override val noComputers = "Компьютеров пока нет."
    override val pairComputer = "Привязать компьютер"
    override val pairHint = "На компьютере: расширение Unitool в браузере → «Привязать телефон», или команда unitool browser pair. Отсканируйте QR-код с экрана."
    override val noCamera = "Здесь нет камеры для QR-кода."
    override fun pairedOn(date: String) = "Привязан $date"
    override fun lastRequest(date: String, site: String) = "Последний запрос: $date, $site"
    override val noRequestsYet = "Запросов ещё не было"
    override val journal = "Журнал"
    override fun outcome(outcome: Outcome) = when (outcome) {
        Outcome.Filled -> "отправлен"
        Outcome.Denied -> "отказано"
        Outcome.TimedOut -> "истекло"
        Outcome.Refused -> "отклонено телефоном"
    }
    override val forget = "Забыть компьютер"
    override fun confirmForget(name: String) = "Забыть компьютер «$name»"
    override fun listening(port: Int) = "Телефон виден привязанным компьютерам в этой сети, пока «Пароли» открыты."
    override val notListening = "Телефон слушает сеть, только пока «Пароли» открыты на экране."
    override val listenFailed = "Не получилось открыть порт в сети. Проверьте Wi-Fi и разрешите Unitool доступ к локальной сети."

    override fun pairQuestion(name: String) = "Привязать «$name»?"
    override val pairFingerprint = "Ключ SSH компьютера — сверьте с экраном компьютера:"
    override val pairExplain = "Этот компьютер сможет просить пароли. Каждый раз вы увидите запрос и подтвердите его."
    override val pairYes = "Привязать"
    override val pairWaiting = "Ждём компьютер…"
    override val pairWaitingHint = "Телефон и компьютер должны быть в одной сети. Не закрывайте «Пароли»."
    override fun paired(name: String) = "Привязано: $name"
    override fun pairProblem(problem: BrowserLink.PairProblem) = when (problem) {
        BrowserLink.PairProblem.NotACode -> "Это не QR-код привязки Unitool."
        BrowserLink.PairProblem.KeyNotSupported -> "Ключ SSH этого компьютера не подходит: нужен ssh-ed25519 или ssh-rsa от 2048 бит."
        BrowserLink.PairProblem.TimedOut -> "Компьютер не подключился за 5 минут. Начните привязку на компьютере заново."
        BrowserLink.PairProblem.NoNetwork -> "Не получилось слушать сеть. Проверьте Wi-Fi и разрешите Unitool доступ к локальной сети."
        BrowserLink.PairProblem.NotSaved -> "Не получилось сохранить: список компьютеров не открыт."
        BrowserLink.PairProblem.Failed -> "Привязка не удалась."
    }
    override val close = "Закрыть"
    override val cancel = "Отмена"

    override val requestTitle = "Запрос пароля"
    override fun requestFrom(computer: String, browser: String) = if (browser.isBlank()) computer else "$computer · $browser"
    override val computerSays = "по словам компьютера"
    override val asciiForm = "В адресе:"
    override val chooseEntry = "Какую запись отправить:"
    override val noEntriesForSite = "Для этого сайта записей нет. Найдите нужную среди всех:"
    override val searchAll = "Поиск по всем записям"
    override val showAll = "Другая запись…"
    override fun mismatch(entrySite: String, requested: String) =
        "Сайт записи — $entrySite, а просит $requested. Это может быть поддельный сайт."
    override val send = "Отправить"
    override val deny = "Отказать"
    override fun secondsLeft(seconds: Long) = "Осталось $seconds с"
    override fun confirmSend(site: String, computer: String) = "Отправить пароль для $site на «$computer»"
}

internal object BrowserStringsEn : BrowserStrings {
    override val computers = "Computers"
    override val computersTitle = "Computers"
    override val computersIntro = "A paired computer can ask for the password of the site open in its browser. Each time the phone shows the request, you pick the entry and confirm. The password is encrypted to the computer's SSH key and goes only over your Wi-Fi."
    override val noComputers = "No computers yet."
    override val pairComputer = "Pair a computer"
    override val pairHint = "On the computer: the Unitool extension in the browser → \"Pair a phone\", or the command unitool browser pair. Scan the QR code it shows."
    override val noCamera = "There is no camera here for the QR code."
    override fun pairedOn(date: String) = "Paired $date"
    override fun lastRequest(date: String, site: String) = "Last request: $date, $site"
    override val noRequestsYet = "No requests yet"
    override val journal = "Journal"
    override fun outcome(outcome: Outcome) = when (outcome) {
        Outcome.Filled -> "sent"
        Outcome.Denied -> "denied"
        Outcome.TimedOut -> "expired"
        Outcome.Refused -> "refused by the phone"
    }
    override val forget = "Forget this computer"
    override fun confirmForget(name: String) = "Forget the computer \"$name\""
    override fun listening(port: Int) = "Paired computers in this network can find the phone while Passwords is open."
    override val notListening = "The phone listens to the network only while Passwords is on screen."
    override val listenFailed = "Couldn't open a port in the network. Check the Wi-Fi and allow Unitool to use the local network."

    override fun pairQuestion(name: String) = "Pair \"$name\"?"
    override val pairFingerprint = "The computer's SSH key — check it against the computer's screen:"
    override val pairExplain = "This computer will be able to ask for passwords. You will see and confirm every request."
    override val pairYes = "Pair"
    override val pairWaiting = "Waiting for the computer…"
    override val pairWaitingHint = "The phone and the computer must be in the same network. Keep Passwords open."
    override fun paired(name: String) = "Paired: $name"
    override fun pairProblem(problem: BrowserLink.PairProblem) = when (problem) {
        BrowserLink.PairProblem.NotACode -> "This is not a Unitool pairing code."
        BrowserLink.PairProblem.KeyNotSupported -> "This computer's SSH key won't do: it needs ssh-ed25519, or ssh-rsa of 2048 bits or more."
        BrowserLink.PairProblem.TimedOut -> "The computer didn't connect within 5 minutes. Start pairing on the computer again."
        BrowserLink.PairProblem.NoNetwork -> "Couldn't listen to the network. Check the Wi-Fi and allow Unitool to use the local network."
        BrowserLink.PairProblem.NotSaved -> "Couldn't save it: the list of computers isn't open."
        BrowserLink.PairProblem.Failed -> "Pairing failed."
    }
    override val close = "Close"
    override val cancel = "Cancel"

    override val requestTitle = "Password request"
    override fun requestFrom(computer: String, browser: String) = if (browser.isBlank()) computer else "$computer · $browser"
    override val computerSays = "as the computer says"
    override val asciiForm = "In the address:"
    override val chooseEntry = "Which entry to send:"
    override val noEntriesForSite = "No entries for this site. Find the one you need among all of them:"
    override val searchAll = "Search all entries"
    override val showAll = "Another entry…"
    override fun mismatch(entrySite: String, requested: String) =
        "The entry is for $entrySite, but $requested is asking. This may be a fake site."
    override val send = "Send"
    override val deny = "Deny"
    override fun secondsLeft(seconds: Long) = "$seconds s left"
    override fun confirmSend(site: String, computer: String) = "Send the password for $site to \"$computer\""
}

internal val BrowserStringTables: Map<String, BrowserStrings> = mapOf(
    "ru" to BrowserStringsRu,
    "en" to BrowserStringsEn,
)
