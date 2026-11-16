package com.jidan.receiving.ui.login

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.FragmentLoginBinding
import com.jidan.receiving.ui.update.bindUpdateEntry
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {
    private var binding: FragmentLoginBinding? = null
    private val vm: LoginViewModel by viewModels {
        LoginViewModel.Factory(requireContext().appContainer())
    }

    private var syncing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentLoginBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        if (arguments?.getBoolean(ARG_EXPIRED) == true) {
            vm.markExpired()
            arguments?.remove(ARG_EXPIRED)
        }

        b.serverUrl.addTextChangedListener(simpleWatcher { vm.onServerUrl(it) })
        b.phone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val digits = digitsPhone(s?.toString().orEmpty())
                vm.onPhone(digits)
                val formatted = formatPhoneDisplay(digits)
                if (s?.toString() != formatted) {
                    syncing = true
                    s?.replace(0, s.length, formatted)
                    syncing = false
                }
                if (digits.length == 11) b.password.requestFocus()
            }
        })
        b.password.addTextChangedListener(simpleWatcher { vm.onPassword(it) })
        b.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                onAction()
                true
            } else {
                false
            }
        }
        b.togglePassword.setOnClickListener { vm.togglePassword() }
        b.action.setOnClickListener { onAction() }
        b.serverUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                requireContext().appContainer().prefs.serverUrl = b.serverUrl.text?.toString().orEmpty()
                requireContext().appContainer().apiClient.invalidate()
                vm.refreshVersion()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { render(it) }
            }
        }
    }

    private fun onAction() {
        val b = binding ?: return
        when (vm.ui.value.step) {
            LoginStep.SERVER -> b.serverUrl.requestFocus()
            LoginStep.PHONE -> b.phone.requestFocus()
            LoginStep.PASSWORD -> b.password.requestFocus()
            LoginStep.READY -> vm.login {
                val nav = findNavController()
                val c = requireContext().appContainer()
                nav.navigate(R.id.action_login_to_create)
                val resumeSummary = c.resumeSummaryAfterLogin
                c.resumeSummaryAfterLogin = false
                if (c.sessionStore.exists()) {
                    nav.navigate(R.id.action_create_to_weigh)
                    if (resumeSummary) {
                        nav.navigate(R.id.action_weigh_to_summary)
                    }
                }
            }
            LoginStep.BUSY -> Unit
        }
    }

    private fun render(state: LoginUiState) {
        val b = binding ?: return
        syncing = true
        if (b.serverUrl.text?.toString() != state.serverUrl) {
            b.serverUrl.setText(state.serverUrl)
            b.serverUrl.setSelection(b.serverUrl.text?.length ?: 0)
        }
        val phoneDisp = formatPhoneDisplay(state.phone)
        if (b.phone.text?.toString() != phoneDisp) {
            b.phone.setText(phoneDisp)
            b.phone.setSelection(b.phone.text?.length ?: 0)
        }
        if (b.password.text?.toString() != state.password) {
            b.password.setText(state.password)
            b.password.setSelection(b.password.text?.length ?: 0)
        }
        syncing = false

        val pwType = if (state.showPassword) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        if (b.password.inputType != pwType) {
            b.password.inputType = pwType
            b.password.setSelection(b.password.text?.length ?: 0)
        }
        b.togglePassword.contentDescription = getString(
            if (state.showPassword) R.string.login_hide_password else R.string.login_show_password,
        )

        b.error.text = state.error
        val bad = state.error.isNotBlank() && !state.expiredHint
        b.fields.background = ContextCompat.getDrawable(
            requireContext(),
            if (bad) R.drawable.bg_login_fields_bad else R.drawable.bg_login_fields,
        )

        b.updateEntry.bindUpdateEntry(state.update)

        b.action.isEnabled = state.step != LoginStep.BUSY
        when (state.step) {
            LoginStep.BUSY -> {
                b.action.text = getString(R.string.login_busy)
                stylePrimary(b)
            }
            LoginStep.SERVER -> {
                b.action.text = getString(R.string.login_need_server)
                styleTodo(b)
            }
            LoginStep.PHONE -> {
                b.action.text = getString(R.string.login_need_phone)
                styleTodo(b)
            }
            LoginStep.PASSWORD -> {
                b.action.text = getString(R.string.login_need_password)
                styleTodo(b)
            }
            LoginStep.READY -> {
                b.action.text = getString(
                    if (state.error.contains("连不上")) R.string.login_retry else R.string.login_go,
                )
                stylePrimary(b)
            }
        }
    }

    private fun stylePrimary(b: FragmentLoginBinding) {
        b.action.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent))
        b.action.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_accent))
    }

    private fun styleTodo(b: FragmentLoginBinding) {
        b.action.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.warn_bg))
        b.action.setTextColor(ContextCompat.getColor(requireContext(), R.color.warn_ink))
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (syncing) return
            onChange(s?.toString().orEmpty())
        }
    }

    companion object {
        const val ARG_EXPIRED = "expired"
    }
}
