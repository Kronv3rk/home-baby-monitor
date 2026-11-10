import com.homebabymonitor.core.*

fun main() {
    val capture = CaptureRecoveryGate()
    capture.start(1000)
    check(capture.next(12_000) == CaptureRecoveryGate.Action.NONE)
    check(capture.next(16_000) == CaptureRecoveryGate.Action.RESTART)
    check(capture.next(17_000) == CaptureRecoveryGate.Action.NONE)
    capture.frame(19_000)
    check(capture.next(26_000) == CaptureRecoveryGate.Action.NONE)
    check(capture.next(30_000) == CaptureRecoveryGate.Action.NONE) // still cooling down
    check(capture.next(40_000) == CaptureRecoveryGate.Action.RESTART)
    check(capture.next(61_000) == CaptureRecoveryGate.Action.RESTART)
    check(capture.next(82_000) == CaptureRecoveryGate.Action.EXHAUSTED)
    check(capture.next(90_000) == CaptureRecoveryGate.Action.NONE) // do not flood warnings
    check(capture.next(320_000) == CaptureRecoveryGate.Action.RESTART)
    capture.frame(321_000)
    check(capture.next(331_000) == CaptureRecoveryGate.Action.NONE)

    val alarms = IncidentAlarmController(reminderIntervalMillis = 60_000)
    check(!alarms.observe(null, 0))
    check(alarms.observe("VIDEO_MISSING", 100))
    check(!alarms.observe("VIDEO_MISSING", 20_000))
    check(alarms.observe("VIDEO_MISSING", 61_000))
    alarms.acknowledge()
    check(!alarms.observe("VIDEO_MISSING", 180_000))
    check(alarms.observe("HEARTBEAT_MISSING", 180_001)) // new problem
    check(!alarms.observe(null, 200_000))
    check(alarms.observe("VIDEO_MISSING", 200_001)) // new incident

    val watchdog = SessionWatchdog(
        startupTimeoutMillis = 30_000,
        heartbeatTimeoutMillis = 8_000,
        videoTimeoutMillis = 12_000,
        reconnectTimeoutMillis = 8_000
    )
    watchdog.started(0)
    check(watchdog.problem(29_999) == null)
    check(watchdog.problem(30_000) == SessionWatchdog.Problem.NEVER_CONNECTED)
    watchdog.connected(31_000)
    watchdog.heartbeat(31_000)
    watchdog.videoFrame(31_000)
    watchdog.disconnected(32_000)
    check(watchdog.problem(39_999) == null)
    check(watchdog.problem(40_000) == SessionWatchdog.Problem.CONNECTION_LOST)
    watchdog.connected(41_000)
    watchdog.heartbeat(41_000)
    watchdog.videoFrame(41_000)
    check(watchdog.problem(41_001) == null)
    println("Capture recovery, repeated/acknowledged alarms, reconnect watchdog: OK")
}
