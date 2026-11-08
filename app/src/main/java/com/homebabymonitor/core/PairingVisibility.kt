package com.homebabymonitor.core

object PairingVisibility {
    fun show(connected: Boolean, revealedByUser: Boolean): Boolean =
        !connected || revealedByUser
}
