package ru.vesc.ble.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.vesc.ble.*
import android.text.InputFilter
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import ru.vesc.ble.databinding.FragmentPhonesBinding
import ru.vesc.ble.databinding.ItemPhoneBinding

class PhonesFragment : Fragment(R.layout.fragment_phones) {

    private val vm: ModuleViewModel by activityViewModels()

    /* Состояние приходит раз в секунду из-за обратного отсчёта. Пересобирать
       список на каждый тик — значит терять прокрутку и мигать картинкой. */
    private var rendered: List<PeerRow>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPhonesBinding.bind(view)
        b.back.setOnClickListener { vm.goTo(Screen.HOME) }
        rendered = null

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s ->
                    b.counter.text = "Занято ${s.peers.size} из ${s.status?.maxPeers ?: 8}"
                    b.empty.isVisible = s.peers.isEmpty()

                    if (s.peers == rendered) return@collect
                    rendered = s.peers

                    b.list.removeAllViews()
                    s.peers.forEach { p ->
                        val row = ItemPhoneBinding.inflate(
                            LayoutInflater.from(requireContext()), b.list, false
                        )
                        row.name.text =
                            if (p.isThisPhone) "${p.name} — ${getString(R.string.phones_this)}"
                            else p.name
                        row.rename.setOnClickListener { askName(p.index, p.name) }
                        row.forget.setOnClickListener { askForget(p.index, p.name) }
                        b.list.addView(row.root)
                    }
                }
            }
        }
    }

    private fun askName(index: Int, current: String) {
        val field = EditText(requireContext()).apply {
            setText(current)
            // Имя в память модуля кладётся как есть, а поле там 18 байт.
            // В UTF-8 кириллица по два байта, поэтому режем по байтам.
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
            .setTitle(R.string.phones_rename)
            .setView(field)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                vm.rename(index, field.text.toString())
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun askForget(index: Int, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.phones_forget)
            .setMessage("Удалить «$name»? Этот телефон потеряет доступ к самокату " +
                        "и, чтобы вернуть его, должен будет снова ввести пароль " +
                        "в первую минуту после включения.")
            .setPositiveButton(R.string.phones_forget) { _, _ -> vm.forget(index) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
