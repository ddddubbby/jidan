package com.jidan.receiving.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.DeviceFactory
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.FragmentHomeBinding

/**
 * P6-1 登录后的占位页；P6-3 换成建单四步。
 * 显示当前磅秤员姓名，可退出登录（APP-12）。
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
        val session = requireContext().appContainer().session.value
        b.title.text = session?.name ?: getString(R.string.home_title)
        b.variant.text = getString(
            R.string.variant_line,
            DeviceFactory.variantName,
            BuildConfig.VERSION_NAME,
        )
        b.logout.setOnClickListener {
            requireContext().appContainer().clearSession()
            findNavController().navigate(R.id.action_home_to_login)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
