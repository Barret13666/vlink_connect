package ru.vlink.connect.ui

import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.vlink.connect.*
import ru.vlink.connect.databinding.FragmentBridgesBinding
import ru.vlink.connect.databinding.ItemBridgeBinding

/**
 * Мосты UART-дисплея: список найденных и мастер привязки.
 *
 * Экран один на два разных дела, и это намеренно. Привязка и просьба
 * «отойди в сторону» относятся к одному и тому же устройству, а человек,
 * пришедший сюда, обычно не знает заранее, что именно ему нужно: он видит,
 * что дисплей не работает, и разбирается по ходу.
 */
class BridgesFragment : Fragment(R.layout.fragment_bridges) {

    private val vm: ModuleViewModel by activityViewModels()

    /* Список перерисовываем только при изменении: состояние приходит раз в
       секунду из-за обратного отсчёта, а пересборка теряет прокрутку.
       В ключ входит не только сам список, но и то, от чего зависит
       доступность кнопок. Иначе запущенный мастер не погасил бы их: список
       остался прежним, и перерисовки бы не случилось. */
    private var rendered: Triple<List<FoundModule>, Boolean, BridgeStep>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentBridgesBinding.bind(view)
        rendered = null

        b.back.setOnClickListener {
            vm.bridgeDisconnect()
            // Если модуль ещё на связи — возвращаемся к нему, если мастер уже
            // отпустил его, возвращаться некуда, кроме поиска.
            vm.goTo(if (vm.ui.value.link == Link.READY) Screen.HOME else Screen.SCAN)
        }
        b.rescan.setOnClickListener { vm.startScan() }

        vm.startScan()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s ->
                    renderProgress(b, s)
                    renderState(b, s)

                    val list = s.bridges
                    b.empty.isVisible = list.isEmpty() && s.bridgeStep == BridgeStep.IDLE

                    val key = Triple(list, s.status?.authed == true, s.bridgeStep)
                    if (key == rendered) return@collect
                    rendered = key

                    b.list.removeAllViews()
                    list.forEach { m ->
                        val row = ItemBridgeBinding.inflate(
                            LayoutInflater.from(requireContext()), b.list, false
                        )
                        row.name.text = m.name
                        row.detail.text = "${m.id} · ${m.rssi} dBm"
                        row.bind.setOnClickListener { askBind(m) }
                        row.check.setOnClickListener { vm.bridgeInspect(m) }
                        row.forget.setOnClickListener { askForget(m) }
                        // Привязывать можно только пройдя пароль: окно в
                        // модуле открывает тот же сеанс, в котором его
                        // вводили. Кнопку не прячем, а гасим — иначе
                        // непонятно, куда она делась.
                        row.bind.isEnabled = s.status?.authed == true &&
                                             s.bridgeStep == BridgeStep.IDLE
                        // Опрос ничего не меняет, поэтому доступен всегда:
                        // он и нужен как раз тогда, когда войти в модуль не
                        // получается.
                        row.check.isEnabled = s.bridgeStep == BridgeStep.IDLE
                        row.forget.isEnabled = s.bridgeStep == BridgeStep.IDLE
                        b.list.addView(row.root)
                    }
                }
            }
        }
    }

    private fun renderState(b: FragmentBridgesBinding, s: UiState) {
        val st = s.bridgeStatus
        if (st == null) {
            b.state.isVisible = false
            return
        }
        b.state.isVisible = true
        b.state.text = getString(R.string.bridges_state_head) + "\n" +
                       BridgeProtocol.summary(st)
    }

    private fun renderProgress(b: FragmentBridgesBinding, s: UiState) {
        if (s.bridgeStep == BridgeStep.IDLE && s.bridgeLog.isEmpty()) {
            b.progress.isVisible = false
            return
        }
        b.progress.isVisible = true

        val head = when (s.bridgeStep) {
            BridgeStep.DONE   -> getString(R.string.bridges_after_bind) + "\n\n"
            BridgeStep.FAILED -> "Привязка не удалась\n\n"
            BridgeStep.IDLE   -> ""
            else              -> "Иду по шагам, не выключайте самокат\n\n"
        }
        b.progress.text = head + s.bridgeLog.joinToString("\n") { "· $it" }
    }

    private fun askForget(m: FoundModule) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bridges_forget)
            .setMessage(R.string.bridges_forget_warning)
            .setPositiveButton(R.string.bridges_forget) { _, _ -> vm.bridgeForget(m) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun askBind(m: FoundModule) {
        val field = EditText(requireContext()).apply {
            setText("Мост дисплея")
            // Имя ляжет в память модуля как есть, а поле там 18 байт.
            // Кириллица в UTF-8 по два байта, поэтому режем по байтам.
            filters = arrayOf(InputFilter { src, st, en, dest, dstart, dend ->
                val base = dest.toString().removeRange(dstart, dend)
                var bytes = base.toByteArray(Charsets.UTF_8).size
                val kept = StringBuilder()
                for (i in st until en) {
                    val add = src[i].toString().toByteArray(Charsets.UTF_8).size
                    if (bytes + add > VescProtocol.NAME_BYTES) break
                    kept.append(src[i]); bytes += add
                }
                if (kept.length == en - st) null else kept.toString()
            })
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bridges_bind)
            .setMessage(R.string.bridges_bind_warning)
            .setView(field)
            .setPositiveButton(R.string.bridges_bind) { _, _ ->
                vm.bridgeBind(m, field.text.toString())
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

}
