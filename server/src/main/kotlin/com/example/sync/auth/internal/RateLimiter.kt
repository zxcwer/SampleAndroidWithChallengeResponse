package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** 1 分固定窓のシンプルなレートリミッタ(単一プロセス内)。 */
@Component
class RateLimiter(private val clock: Clock) {
    private class Window(val minute: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun check(key: String, limitPerMinute: Int) {
        val minute = clock.millis() / 60_000
        val w = windows.compute(key) { _, cur ->
            if (cur == null || cur.minute != minute) Window(minute, 1) else cur.also { it.count++ }
        }!!
        if (w.count > limitPerMinute) {
            throw ApiException(ErrorCode.RATE_LIMITED, "Too many requests")
        }
        if (windows.size > 10_000) {
            windows.entries.removeIf { it.value.minute != minute }
        }
    }
}
