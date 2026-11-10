import com.homebabymonitor.core.*
import java.nio.ByteBuffer

fun main() {
    val budget = NightFrameBudget(maxPixels = 1920L * 1080, maxFps = 30,
        slowFrameNanos = 12_000_000, maxSlowFrames = 3, coolDownNanos = 30_000_000_000)
    check(budget.decide(false, 1280, 720, 30, 0) == NightFrameBudget.Decision.DISABLED)
    check(budget.decide(true, 3840, 2160, 60, 0) == NightFrameBudget.Decision.TOO_EXPENSIVE)
    check(budget.decide(true, 2560, 1440, 30, 0) == NightFrameBudget.Decision.TOO_EXPENSIVE)
    check(budget.decide(true, 1920, 1080, 60, 0) == NightFrameBudget.Decision.TOO_EXPENSIVE)
    check(budget.decide(true, 1920, 1080, 30, 0) == NightFrameBudget.Decision.SOFTWARE)
    repeat(2) {
        budget.observeCost(20_000_000, it * 1_000_000_000L)
        check(budget.decide(true, 1920, 1080, 30, it * 1_000_000_000L) == NightFrameBudget.Decision.SOFTWARE)
    }
    budget.observeCost(20_000_000, 2_000_000_000L)
    check(budget.decide(true, 1920, 1080, 30, 3_000_000_000L) == NightFrameBudget.Decision.OVERLOADED)
    check(budget.decide(true, 1920, 1080, 30, 31_999_000_000L) == NightFrameBudget.Decision.OVERLOADED)
    check(budget.decide(true, 1920, 1080, 30, 32_000_000_000L) == NightFrameBudget.Decision.SOFTWARE)
    budget.observeCost(500_000, 32_000_100_000L)

    // Strided source/destination: padding must not be modified, original buffer positions preserved.
    val y = ByteBuffer.allocateDirect(12)
    val dest = ByteBuffer.allocateDirect(16)
    for (i in 0..11) y.put(i, (i * 15).toByte())
    for (i in 0..15) dest.put(i, 99.toByte())
    y.position(1); dest.position(2)
    NightLumaTransformer.liftLuma(y, 4, dest, 5, 3, 3)
    check(y.position() == 1 && dest.position() == 2)
    for (row in 0..2) {
        for (x in 0..2) {
            val original = (y.get(row * 4 + x).toInt() and 255)
            val expected = (original * 1.25 + 14).toInt().coerceIn(0, 255)
            check((dest.get(row * 5 + x).toInt() and 255) == expected)
        }
        check(dest.get(row * 5 + 3).toInt() == 99)
        check(dest.get(row * 5 + 4).toInt() == 99)
    }
    NightLumaTransformer.copyPlane(y, 4, dest, 5, 3, 3)
    check(dest.get(0) == y.get(0))
    check(dest.get(5) == y.get(4))
    check(dest.get(10) == y.get(8))

    check(NightFrameBudget().decide(true, Int.MAX_VALUE, Int.MAX_VALUE, 60, 0) ==
        NightFrameBudget.Decision.TOO_EXPENSIVE)
    println("Night pipeline: 4K zero-copy bypass, CPU overload fallback, stride-safe bulk copy: OK")
}
