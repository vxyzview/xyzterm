package com.xyzterm.terminal

import android.net.ConnectivityManager
import com.xyzterm.utils.application

/** DNS servers of the active Android network, so the guest does not hardcode a public resolver. */
internal fun systemDns(): List<String> {
    val manager = application?.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val network = manager.activeNetwork ?: return emptyList()
    return manager
        .getLinkProperties(network)
        ?.dnsServers
        ?.filterNot { it.isLinkLocalAddress }
        ?.mapNotNull { it.hostAddress?.substringBefore('%') }
        .orEmpty()
}
