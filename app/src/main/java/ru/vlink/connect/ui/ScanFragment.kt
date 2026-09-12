package ru.vlink.connect.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.vlink.connect.*
import ru.vlink.connect.databinding.FragmentScanBinding

class ScanFragment : Fragment(R.layout.fragment_scan) {

    private val vm: ModuleViewModel by activityViewModels()

    /* Список перерисовываем только при изменении: состояние приходит часто,
       а пересборка на каждое обновление теряет прокрутку. */
    private var rendered: List<FoundModule>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentScanBinding.bind(view)
        rendered = null

        b.swipe.setOnRefreshListener {
            vm.startScan()
            // Кружок держим ровно до появления первых результатов. Дальше он
            // не сообщает ничего нового, зато накрывает собой список и
            // перехватывает нажатия. Что поиск продолжается, видно по
            // надписи на кнопке.
            viewLifecycleOwner.lifecycleScope.launch {
                delay(1000)
                b.swipe.isRefreshing = false
            }
        }

        b.scanButton.setOnClickListener {
            if (vm.ui.value.link == Link.SCANNING) vm.stopScan() else vm.startScan()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s ->
                    val scanning = s.link == Link.SCANNING
                    b.scanButton.setText(if (scanning) R.string.scan_stop else R.string.scan_start)
                    b.empty.isVisible = s.modules.isEmpty()

                    val bridges = s.bridges
                    b.bridgesTitle.isVisible = bridges.isNotEmpty()
                    b.bridgesHint.isVisible = bridges.isNotEmpty()

                    if (s.found == rendered) return@collect
                    rendered = s.found

                    b.list.removeAllViews()
                    s.modules.forEach { m ->
                        b.list.addView(
                            row(b.list, m.name, "${m.id} · ${m.rssi} dBm") { vm.connect(m) }
                        )
                    }

                    // Мосты показываем, но нажимать их незачем: привязка
                    // идёт с экрана модуля, а освободить модуль по воздуху
                    // нельзя — только снятием питания с моста.
                    b.bridgeList.removeAllViews()
                    bridges.forEach { m ->
                        b.bridgeList.addView(
                            row(b.bridgeList, m.name, "${m.id} · ${m.rssi} dBm", null)
                        )
                    }
                }
            }
        }
    }

    /* Родителя передаём обязательно, пусть и с attachToRoot = false: без него
       inflate выбрасывает layout_width из разметки, и строка съёживается до
       ширины текста. */
    private fun row(
        parent: android.view.ViewGroup,
        title: String,
        subtitle: String,
        onClick: (() -> Unit)?
    ): View {
        val v = LayoutInflater.from(requireContext())
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        v.findViewById<TextView>(android.R.id.text1).text = title
        v.findViewById<TextView>(android.R.id.text2).text = subtitle
        if (onClick != null) v.setOnClickListener { onClick() }
        return v
    }

}
