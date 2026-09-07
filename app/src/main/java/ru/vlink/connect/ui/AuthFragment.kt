package ru.vlink.connect.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.vlink.connect.*
import androidx.core.view.isVisible
import ru.vlink.connect.databinding.FragmentAuthBinding

/**
 * Один экран на два случая. Модуль не настроен — просим придумать логин и
 * пароль. Настроен — просим только пароль: логин модуль присылает сам,
 * иначе телефон друга, которого владелец добавляет впервые, не смог бы
 * посчитать ключ.
 */
class AuthFragment : Fragment(R.layout.fragment_auth) {

    private val vm: ModuleViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentAuthBinding.bind(view)

        b.login.filters = arrayOf(
            VescCredentials.loginFilter { blocked ->
                b.loginBox.error = VescCredentials.blockedHint(blocked, "логине")
            }
        )
        b.password.filters = arrayOf(
            VescCredentials.passwordFilter { blocked ->
                b.passwordBox.error = VescCredentials.blockedHint(blocked, "пароле")
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s ->
                    val setup = s.status?.empty ?: true
                    val usable = s.status?.let { it.open && !it.locked } ?: false
                    // Прошивки до кадра 0x88 логин не сообщают. Тогда спрашиваем.
                    val askLogin = setup || s.login.isEmpty()

                    b.title.setText(
                        if (setup) R.string.auth_title_setup else R.string.auth_title_login
                    )
                    b.hint.text = when {
                        setup -> getString(R.string.auth_hint_setup)
                        s.login.isEmpty() ->
                            "Модуль не сообщил логин — похоже, на нём прошивка до " +
                            "появления такой возможности. Введите логин вручную."
                        else -> "Вход как «${s.login}». " + getString(R.string.auth_latin_only)
                    }

                    b.loginBox.isVisible = askLogin
                    b.repeatBox.isVisible = setup
                    b.go.setText(if (setup) R.string.auth_do_setup else R.string.auth_do_login)
                    b.go.isEnabled = usable && s.busy == null
                    // Ничего не пишем в поля из подписки: состояние
                    // обновляется раз в секунду вместе с обратным отсчётом,
                    // и любой setText сбивал бы курсор прямо при наборе.

                    b.go.setOnClickListener {
                        b.loginBox.error = null
                        b.passwordBox.error = null
                        val pass = b.password.text?.toString().orEmpty()
                        if (setup) {
                            vm.setup(
                                b.login.text?.toString().orEmpty(),
                                pass,
                                b.repeat.text?.toString().orEmpty()
                            )
                        } else {
                            vm.login(pass, b.login.text?.toString().orEmpty())
                        }
                    }
                }
            }
        }
    }
}
