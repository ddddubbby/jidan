package com.jidan.receiving.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.DeviceFactory
import com.jidan.receiving.R
import com.jidan.receiving.SimConsoleHook
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.FragmentHomeBinding
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.widgets.DeviceCardBinder
import kotlinx.coroutines.launch

/**
 * P6-1/P6-2 登录后的占位页：设备卡片 +（模拟版）模拟台；P6-3 换成建单。
 */
class HomeFragment : Fragment() {
    private var binding: FragmentHomeBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentHomeBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        val c = requireContext().appContainer()
        val session = c.session.value
        b.title.text = session?.name ?: getString(R.string.home_title)
        b.variant.text = getString(
            R.string.variant_line,
            DeviceFactory.variantName,
            BuildConfig.VERSION_NAME,
        )
        b.logout.setOnClickListener {
            c.clearSession()
            findNavController().navigate(R.id.action_home_to_login)
        }
        b.openDevices.setOnClickListener {
            DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG)
        }

        fun openSheet() {
            DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG)
        }

        fun refreshCards() {
            DeviceCardBinder.bindScale(
                binding = b.scaleCard,
                gate = c.bluetoothGate,
                status = c.scale.linkStatus.value,
                pairedId = c.scale.pairedId.value,
                modelLabel = c.options.value.get("device.scale-model"),
                batteryLow = c.scale.batteryLow.value,
                onAction = { act ->
                    if (act == LinkActionKind.SEARCH || act == LinkActionKind.RESEARCH ||
                        act == LinkActionKind.OPEN_BLUETOOTH || act == LinkActionKind.GRANT_PERMISSION
                    ) {
                        openSheet()
                    }
                },
                onOpenSheet = ::openSheet,
            )
            DeviceCardBinder.bindPrinter(
                binding = b.printerCard,
                gate = c.bluetoothGate,
                status = c.printer.linkStatus.value,
                pairedId = c.printer.pairedId.value,
                modelLabel = c.options.value.get("device.printer-model"),
                issue = c.printer.printerStatus.value,
                onAction = { openSheet() },
                onOpenSheet = ::openSheet,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { c.scale.linkStatus.collect { refreshCards() } }
                launch { c.scale.pairedId.collect { refreshCards() } }
                launch { c.scale.batteryLow.collect { refreshCards() } }
                launch { c.printer.linkStatus.collect { refreshCards() } }
                launch { c.printer.pairedId.collect { refreshCards() } }
                launch { c.printer.printerStatus.collect { refreshCards() } }
            }
        }
        refreshCards()

        if (b.simHost.childCount == 0) {
            SimConsoleHook.attach(b.simHost)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
