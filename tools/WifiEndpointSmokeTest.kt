import com.homebabymonitor.core.LocalAddress
import com.homebabymonitor.core.WifiCandidate
import com.homebabymonitor.core.WifiEndpointSelector

fun main() {
    val networks = listOf(
        WifiCandidate(false, true, listOf("10.32.0.18")),
        WifiCandidate(true, false, listOf("192.168.21.10"))
    )
    check(WifiEndpointSelector.choose(networks) == "192.168.21.10")
    check(WifiEndpointSelector.choose(listOf(WifiCandidate(true, false,
        listOf("169.254.12.1", "192.168.1.9")))) == "192.168.1.9")
    check(WifiEndpointSelector.choose(listOf(WifiCandidate(false, true,
        listOf("192.168.1.9")))) == null)
    check(WifiEndpointSelector.choose(listOf(WifiCandidate(true, true,
        listOf("8.8.8.8")))) == null)
    check(!LocalAddress.isPrivateIpv4("169.254.10.3"))
    println("WifiEndpointSmokeTest: PASS")
}
