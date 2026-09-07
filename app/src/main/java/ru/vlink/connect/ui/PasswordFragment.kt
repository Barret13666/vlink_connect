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
import ru.vlink.connect.databinding.FragmentPasswordBinding

class PasswordFragment : Fragment(R.layout.fragment_password) {

    private val vm: ModuleViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPasswordBinding.bind(view)
        b.back.setOnClickListener { vm.goTo(Screen.HOME) }

        b.newPassword.filters = arrayOf(
            VescCredentials.passwordFilter { blocked ->
                b.newBox.error = VescCredentials.blockedHint(blocked, "пароле")
            }
        )

        b.applyButton.setOnClickListener {
            b.newBox.error = null
            val pass = b.newPassword.text?.toString().orEmpty()
            when (val v = VescCredentials.checkPassword(pass)) {
                is VescCredentials.Verdict.Warning -> b.newBox.error = v.text
                else -> {}
            }
            vm.changePassword(pass, b.repeat.text?.toString().orEmpty())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { s -> b.applyButton.isEnabled = s.busy == null }
            }
        }
    }
}
