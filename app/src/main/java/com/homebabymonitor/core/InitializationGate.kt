package com.homebabymonitor.core

class InitializationGate {
    private var completed = false
    @Synchronized fun ensure(initialize: () -> Unit) {
        if (completed) return
        initialize()
        completed = true
    }
}
