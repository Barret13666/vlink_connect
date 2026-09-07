/*
 * ============================================================================
 *  VescCredentials.kt — проверка логина и пароля на стороне приложения
 * ============================================================================
 *
 *  Разрешена только печатная латиница. Ограничение не косметическое.
 *
 *  K выводится из БАЙТОВ текста, а одинаковая на вид кириллическая надпись
 *  может дать разные байты:
 *
 *    - «й» бывает одним знаком U+0439, а бывает «и» плюс отдельная
 *      краткость U+0306. То же с «ё». Что именно выдаст клавиатура,
 *      зависит от клавиатуры, а Android их знает много.
 *    - Кириллические «а е о р с у х А В Е К М Н О Р С Т Х» неотличимы на
 *      вид от латинских. Человек, набравший пароль при русской раскладке,
 *      получит другой K и не войдёт, причём на экране всё будет правильно.
 *
 *  Ошибка выглядит как «пароль верный, а модуль не пускает» и ищется
 *  часами. Дешевле не пустить на входе.
 *
 *  Модуль проверяет то же самое сам (cryptoLoginValid, cryptoPasswordValid
 *  в crypto.cpp): приложение сегодня одно и оно своё, а завтра кто-нибудь
 *  напишет второе.
 *
 *  ПРИМЕНЕНИЕ
 *
 *      loginField.filters = arrayOf(
 *          VescCredentials.loginFilter { blocked ->
 *              hint.text = VescCredentials.blockedHint(blocked, "логине")
 *          },
 *          InputFilter.LengthFilter(VescCredentials.MAX_LOGIN)
 *      )
 *
 *  LengthFilter здесь честен именно потому, что латиница односбайтовая:
 *  число знаков совпадает с числом байтов в поле модуля.
 *
 *      when (val v = VescCredentials.checkPassword(text)) {
 *          is VescCredentials.Verdict.Ok      -> { hint.text = ""; ok.isEnabled = true }
 *          is VescCredentials.Verdict.Warning -> { hint.text = v.text; ok.isEnabled = true }
 *          is VescCredentials.Verdict.Error   -> { hint.text = v.text; ok.isEnabled = false }
 *      }
 * ============================================================================
 */

package ru.vesc.ble

import android.text.InputFilter

object VescCredentials {

    /** Поле логина в памяти модуля — 16 байт с завершающим нулём. */
    const val MAX_LOGIN = 15
    const val MAX_PASSWORD = 64

    /** Ниже этой длины предупреждаем, но сохранить даём. */
    const val WEAK_PASSWORD_BELOW = 10

    sealed class Verdict {
        /** Всё в порядке. */
        object Ok : Verdict()
        /** Сохранять можно, но стоит показать текст пользователю. */
        data class Warning(val text: String) : Verdict()
        /** Сохранять нельзя, кнопку блокируем. */
        data class Error(val text: String) : Verdict()
    }

    // ------------------------------------------------------- наборы знаков

    private fun isLoginChar(c: Char) =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
        c == '.' || c == '_' || c == '-'

    /** Печатная часть ASCII вместе с пробелом: длинная фраза из слов —
     *  хороший пароль, отнимать пробел незачем. */
    private fun isPasswordChar(c: Char) = c.code in 0x20..0x7E

    private fun isCyrillic(c: Char) =
        c in '\u0400'..'\u04FF' || c in '\u0500'..'\u052F'

    /** Кириллические знаки, неотличимые на вид от латинских. */
    private val homoglyphs = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c',
        'у' to 'y', 'х' to 'x', 'ѕ' to 's', 'і' to 'i', 'ј' to 'j',
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M',
        'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T',
        'Х' to 'X', 'Ѕ' to 'S', 'І' to 'I', 'Ј' to 'J'
    )

    // -------------------------------------------------------- формулировки

    /**
     * Текст про кириллицу, или null, если её нет. Если ВСЕ кириллические
     * знаки оказались двойниками латинских, скорее всего человек просто не
     * переключил раскладку — тогда показываем, что он хотел набрать.
     */
    private fun cyrillicMessage(s: String, where: String): String? {
        val found = s.filter(::isCyrillic)
        if (found.isEmpty()) return null

        val listed = found.toSortedSet().joinToString(" ") { "«$it»" }
        val head = "Кириллица в $where не допускается. Найдено: $listed."

        return if (found.all { it in homoglyphs }) {
            val asLatin = s.map { homoglyphs[it] ?: it }.joinToString("")
            head + "\n\nЭти буквы выглядят как латинские, но это другие " +
                   "символы — похоже, не переключена раскладка. " +
                   "Латиницей получилось бы «$asLatin»."
        } else {
            head + "\n\nКлюч выводится из байтов текста, а одна и та же " +
                   "надпись на разных клавиатурах даёт разные байты. Вход " +
                   "перестал бы работать без видимой причины."
        }
    }

    private fun otherBadChars(s: String, allowed: (Char) -> Boolean): String? {
        val bad = s.filterNot { allowed(it) || isCyrillic(it) }.toSortedSet()
        if (bad.isEmpty()) return null
        val shown = bad.joinToString(" ") {
            if (it.code < 0x20) "код ${it.code}" else "«$it»"
        }
        return shown
    }

    // ----------------------------------------------------------- проверки

    fun checkLogin(login: String): Verdict {
        if (login.isEmpty()) {
            return Verdict.Error("Логин не может быть пустым.")
        }
        cyrillicMessage(login, "логине")?.let { return Verdict.Error(it) }

        otherBadChars(login, ::isLoginChar)?.let {
            return Verdict.Error(
                "В логине недопустимо: $it.\n\nРазрешены латинские буквы, " +
                "цифры, точка, дефис и подчёркивание."
            )
        }
        if (login.length > MAX_LOGIN) {
            return Verdict.Error(
                "Логин длиннее $MAX_LOGIN знаков. Столько байт отведено под " +
                "него в памяти модуля."
            )
        }
        return Verdict.Ok
    }

    fun checkPassword(password: String): Verdict {
        if (password.isEmpty()) {
            return Verdict.Error("Пароль не может быть пустым.")
        }
        cyrillicMessage(password, "пароле")?.let { return Verdict.Error(it) }

        otherBadChars(password, ::isPasswordChar)?.let {
            return Verdict.Error(
                "В пароле недопустимо: $it.\n\nРазрешены латинские буквы, " +
                "цифры, знаки препинания и пробел."
            )
        }
        if (password.length > MAX_PASSWORD) {
            return Verdict.Error("Пароль длиннее $MAX_PASSWORD знаков.")
        }

        // Дальше — то, что сохранить можно, но лучше сказать вслух.
        if (password != password.trim()) {
            return Verdict.Warning(
                "Пароль начинается или заканчивается пробелом. Он входит в " +
                "пароль, но на экране не виден — при следующем вводе легко " +
                "промахнуться."
            )
        }
        if (password.length < WEAK_PASSWORD_BELOW) {
            return Verdict.Warning(
                "Короткий пароль. Подобрать его по эфиру мешает лимит в три " +
                "попытки, но если кто-то снимет память модуля, перебор пойдёт " +
                "уже без ограничений."
            )
        }
        return Verdict.Ok
    }

    // ------------------------------------------------------ фильтры ввода

    /**
     * Не даёт набрать или вставить запрещённые знаки. Обратный вызов
     * получает то, что было отброшено, — покажите подсказку, иначе символы
     * будут молча пропадать и это выглядит как поломка.
     */
    fun loginFilter(onBlocked: ((String) -> Unit)? = null): InputFilter =
        filterBy(::isLoginChar, onBlocked)

    fun passwordFilter(onBlocked: ((String) -> Unit)? = null): InputFilter =
        filterBy(::isPasswordChar, onBlocked)

    private fun filterBy(
        allowed: (Char) -> Boolean,
        onBlocked: ((String) -> Unit)?
    ) = InputFilter { source, start, end, _, _, _ ->
        val kept = StringBuilder()
        val dropped = StringBuilder()
        for (i in start until end) {
            val c = source[i]
            if (allowed(c)) kept.append(c) else dropped.append(c)
        }
        if (dropped.isEmpty()) {
            null                       // ничего не тронули, пусть идёт как есть
        } else {
            onBlocked?.invoke(dropped.toString())
            kept.toString()
        }
    }

    /** Короткая подсказка к тому, что отбросил фильтр. */
    fun blockedHint(blocked: String, where: String): String =
        cyrillicMessage(blocked, where)
            ?: "Эти знаки в $where не поддерживаются: " +
               blocked.toSortedSet().joinToString(" ") {
                   if (it.code < 0x20) "код ${it.code}" else "«$it»"
               }

    // ------------------------------------------------------- самопроверка

    /** Возвращает список расхождений; пустой — всё как задумано. */
    fun selfTest(): List<String> {
        val bad = mutableListOf<String>()

        fun expect(name: String, got: Verdict, want: Class<*>) {
            if (!want.isInstance(got)) bad += "$name: получено $got"
        }

        expect("owner", checkLogin("owner"), Verdict.Ok::class.java)
        expect("max_2.vesc-01", checkLogin("max_2.vesc-01"), Verdict.Ok::class.java)
        expect("пустой логин", checkLogin(""), Verdict.Error::class.java)
        expect("логин 16 знаков", checkLogin("0123456789abcdef"), Verdict.Error::class.java)
        expect("пробел в логине", checkLogin("my owner"), Verdict.Error::class.java)
        expect("кириллический логин", checkLogin("хозяин"), Verdict.Error::class.java)

        // Двойники: на вид «poccua», на деле кириллица.
        expect("двойники", checkLogin("\u0440\u043e\u0441\u0441\u0443\u0430"),
               Verdict.Error::class.java)

        expect("correct horse", checkPassword("correct horse"), Verdict.Ok::class.java)
        expect("пустой пароль", checkPassword(""), Verdict.Error::class.java)
        expect("кириллический пароль", checkPassword("пароль"), Verdict.Error::class.java)
        expect("короткий пароль", checkPassword("abc"), Verdict.Warning::class.java)
        expect("хвостовой пробел", checkPassword("correct horse "), Verdict.Warning::class.java)

        return bad
    }
}
