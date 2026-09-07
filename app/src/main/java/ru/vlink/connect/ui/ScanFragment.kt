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
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.isVisible
import ru.vlink.connect.databinding.FragmentScanBinding

class ScanFragment : Fragment(R.layout.fragment_scan) {

    private val vm: ModuleViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentScanBinding.bind(view)

        b.scanButton.setOnClickListener {
            if (vm.ui.value.link == Link.SCANNING) vm.stopScan() else vm.startScan()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s ->
                    b.scanButton.setText(
                        if (s.link == Link.SCANNING) R.string.scan_stop else R.string.scan_start
                    )
                    b.empty.isVisible = s.found.isEmpty()

                    if (b.list.childCount != s.found.size) {
                        b.list.removeAllViews()
                        s.found.forEach { m ->
                            val row = LayoutInflater.from(requireContext())
                                .inflate(android.R.layout.simple_list_item_2, b.list, false)
                            row.findViewById<TextView>(android.R.id.text1).text = m.name
                            row.findViewById<TextView>(android.R.id.text2).text =
                                "${m.id} · ${m.rssi} dBm"
                            row.setOnClickListener { vm.connect(m) }
                            b.list.addView(row)
                        }
                    }
                }
            }
        }
    }
}
