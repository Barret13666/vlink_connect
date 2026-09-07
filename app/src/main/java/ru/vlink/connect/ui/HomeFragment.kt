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
import androidx.appcompat.app.AlertDialog
import ru.vlink.connect.databinding.FragmentHomeBinding

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val vm: ModuleViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view)

        b.phones.setOnClickListener { vm.goTo(Screen.PHONES) }
        b.password.setOnClickListener { vm.goTo(Screen.PASSWORD) }

        b.bind.setOnClickListener {
            // Предупреждаем до того, как всплывёт системное окно: без этого
            // человек примет его за постороннее и отменит.
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.home_bind)
                .setMessage(R.string.home_bind_warning)
                .setPositiveButton(R.string.home_bind) { _, _ -> vm.bind() }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s ->
                    b.name.text = s.moduleName
                    val st = s.status
                    val alreadyBound = st?.activePeer != null

                    b.state.text = buildString {
                        append("Вход как «${s.login}»\n")
                        append("Телефонов в памяти: ${st?.peerCount ?: 0} из ${st?.maxPeers ?: 8}\n")
                        append(
                            if (alreadyBound)
                                "Этот телефон привязан — VESC Tool с него работает всегда, " +
                                "не только в первую минуту."
                            else
                                "Этот телефон ещё не привязан. Без привязки VESC Tool " +
                                "подключится, но данных не получит."
                        )
                    }

                    b.bind.isEnabled = !alreadyBound && s.busy == null &&
                                       (st?.peerCount ?: 0) < (st?.maxPeers ?: 8)
                }
            }
        }
    }
}
