package com.jidan.receiving.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.DeviceFactory
import com.jidan.receiving.R
import com.jidan.receiving.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var binding: FragmentHomeBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentHomeBinding.inflate(inflater, container, false)
        b.variant.text = getString(R.string.variant_line, DeviceFactory.variantName, BuildConfig.VERSION_NAME)
        binding = b
        return b.root
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
