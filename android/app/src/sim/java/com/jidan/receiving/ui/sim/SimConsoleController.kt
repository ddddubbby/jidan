package com.jidan.receiving.ui.sim

import android.view.View
import com.jidan.receiving.DeviceFactory
import com.jidan.receiving.databinding.ViewSimConsoleBinding
import com.jidan.receiving.device.fake.FakeScale
import com.jidan.receiving.device.PrinterIssue

/** 绑定模拟台控件到 FakeScale / FakePrinter。 */
class SimConsoleController(
    private val binding: ViewSimConsoleBinding,
) {
    private var caseIdx = 0

    fun bind() {
        val scale = DeviceFactory.fakeScale
        val printer = DeviceFactory.fakePrinter
        binding.placeOne.setOnClickListener {
            val net = FakeScale.CASE_NETS_C[caseIdx % FakeScale.CASE_NETS_C.size]
            caseIdx++
            scale.placeOneBox(net)
        }
        binding.placeThree.setOnClickListener {
            val nets = (0 until 3).map {
                val n = FakeScale.CASE_NETS_C[caseIdx % FakeScale.CASE_NETS_C.size]
                caseIdx++
                n
            }
            scale.placeBoxes(nets)
        }
        binding.placeEmpty.setOnClickListener { scale.placeEmptyBox() }
        binding.remove.setOnClickListener { scale.removeLoad() }
        binding.toggleReach.setOnClickListener {
            scale.reachable = !scale.reachable
            binding.toggleReach.text = if (scale.reachable) "断开秤" else "恢复秤"
        }
        binding.battLow.setOnCheckedChangeListener { _, checked ->
            scale.batteryLowFlag = checked
        }
        binding.noPaper.setOnClickListener { printer.setIssue(PrinterIssue.NO_PAPER) }
        binding.openCover.setOnClickListener { printer.setIssue(PrinterIssue.OPEN_COVER) }
        binding.printerOk.setOnClickListener { printer.setIssue(PrinterIssue.OK) }
        binding.anomalyNotNet.setOnCheckedChangeListener { _, v -> scale.anomalyReadingNotNet = v }
        binding.anomalyStable.setOnCheckedChangeListener { _, v -> scale.anomalyStableBroken = v }
        binding.anomalyTare.setOnCheckedChangeListener { _, v -> scale.anomalyTareIgnored = v }
    }

    companion object {
        fun inflateInto(parent: android.view.ViewGroup): SimConsoleController {
            val inflater = android.view.LayoutInflater.from(parent.context)
            val view = inflater.inflate(com.jidan.receiving.R.layout.view_sim_console, parent, false)
            parent.addView(view)
            val binding = ViewSimConsoleBinding.bind(view)
            return SimConsoleController(binding).also { it.bind() }
        }
    }
}
