import com.homebabymonitor.core.SessionWatchdog

fun main() {
    // The first connection must be observed; merely starting the service is insufficient.
    val initial = SessionWatchdog(startupTimeoutMillis = 30_000, heartbeatTimeoutMillis = 8_000, videoTimeoutMillis = 12_000)
    initial.started(1_000)
    check(initial.problem(30_999) == null)
    check(initial.problem(31_000) == SessionWatchdog.Problem.NEVER_CONNECTED)
    initial.connected(32_000)
    check(initial.problem(32_001) == null)

    // A responsive data channel must not mask a frozen camera feed.
    initial.heartbeat(42_000)
    check(initial.problem(44_001) == SessionWatchdog.Problem.VIDEO_MISSING)
    initial.videoFrame(45_000)
    initial.heartbeat(45_000)
    check(initial.problem(45_001) == null)

    // An active camera doesn't mask a lost control/audio path.
    initial.videoFrame(54_000)
    check(initial.problem(54_001) == SessionWatchdog.Problem.HEARTBEAT_MISSING)

    // Reconnection must start a new grace period for media frames.
    initial.connected(55_000)
    check(initial.problem(56_000) == null)
    initial.heartbeat(66_000)
    check(initial.problem(67_001) == SessionWatchdog.Problem.VIDEO_MISSING)
    println("SessionWatchdogSmokeTest: ALL PASSED")
}
