package ru.vesc.ble

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import ru.vesc.ble.databinding.ActivityMainBinding
import ru.vesc.ble.ui.AuthFragment
import ru.vesc.ble.ui.HomeFragment
import ru.vesc.ble.ui.PasswordFragment
import ru.vesc.ble.ui.PhonesFragment
import ru.vesc.ble.ui.ScanFragment

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: ModuleViewModel by viewModels()
    private var shown: Screen? = null

    /** До Android 12 поиск BLE требовал разрешения на местоположение, с
     *  Android 12 — двух отдельных разрешений. Обе ветки нужны, потому что
     *  minSdk 26. */
    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val ask = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { !it }) {
            Snackbar.make(b.root,
                "Без разрешения на Bluetooth приложение не найдёт модуль",
                Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (!vm.isFake) ask.launch(permissions)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { render(it) }
            }
        }
    }

    private fun render(s: UiState) {
        if (shown != s.screen) {
            shown = s.screen
            val f: Fragment = when (s.screen) {
                Screen.SCAN     -> ScanFragment()
                Screen.AUTH     -> AuthFragment()
                Screen.HOME     -> HomeFragment()
                Screen.PHONES   -> PhonesFragment()
                Screen.PASSWORD -> PasswordFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, f)
                .commit()
        }

        val st = s.status
        val text = when {
            s.busy != null -> s.busy
            st == null -> null
            st.locked -> "Модуль заперт: три неверных пароля. " +
                         "Снимите и подайте питание."
            !st.open -> "Окно настройки закрыто. Снимите и подайте питание, " +
                        "затем подключитесь в первую минуту."
            else -> "До закрытия окна ${st.secondsLeft} с · " +
                    "попыток осталось: ${st.attemptsLeft}"
        }
        b.banner.text = text ?: ""
        b.banner.visibility = if (text == null) View.GONE else View.VISIBLE

        s.message?.let {
            Snackbar.make(b.root, it, Snackbar.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    override fun onBackPressed() {
        val s = vm.ui.value
        when (s.screen) {
            Screen.PHONES, Screen.PASSWORD -> vm.goTo(Screen.HOME)
            Screen.HOME, Screen.AUTH -> vm.disconnect()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
