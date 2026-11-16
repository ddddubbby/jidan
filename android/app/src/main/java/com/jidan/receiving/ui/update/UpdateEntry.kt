package com.jidan.receiving.ui.update

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.core.view.isVisible

/** 把下载入口画到一个 TextView 上；点了用浏览器打开下载地址。登录页与建单页共用。 */
fun TextView.bindUpdateEntry(offer: UpdateOffer?) {
    isVisible = offer != null
    text = offer?.label
    if (offer == null) {
        setOnClickListener(null)
    } else {
        setOnClickListener {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.url)))
        }
    }
}
