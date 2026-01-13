package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider

class ZivpnStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var serverHost: String by store.string(
        key = "zivpn_server_ip",
        defaultValue = ""
    )

    var serverPass: String by store.string(
        key = "zivpn_server_pass",
        defaultValue = ""
    )

    var serverObfs: String by store.string(
        key = "zivpn_server_obfs",
        defaultValue = "hu``hqb`c"
    )

    var portRanges: String by store.string(
        key = "zivpn_port_range",
        defaultValue = "6000-19999"
    )

    var mtu: Int by store.int(
        key = "zivpn_mtu",
        defaultValue = 9000
    )

    var autoBoot: Boolean by store.boolean(
        key = "zivpn_auto_boot",
        defaultValue = false
    )

    var autoReset: Boolean by store.boolean(
        key = "zivpn_auto_reset",
        defaultValue = false
    )

    var resetTimeout: Int by store.int(
        key = "zivpn_reset_timeout",
        defaultValue = 15
    )
}