import com.homebabymonitor.core.InitializationGate

fun main() {
    val gate = InitializationGate()
    var attempts = 0
    check(runCatching { gate.ensure { attempts++; throw IllegalStateException("library not ready") } }.isFailure)
    gate.ensure { attempts++ }
    gate.ensure { attempts++ }
    check(attempts == 2)
    println("InitializationGateSmokeTest: ALL PASSED")
}
