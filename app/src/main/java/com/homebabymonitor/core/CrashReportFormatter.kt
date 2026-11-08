package com.homebabymonitor.core

object CrashReportFormatter {
    fun label(reason: Int): String = when (reason) {
        5 -> "Сбой нативного компонента"
        4 -> "Необработанная ошибка Android"
        6 -> "Приложение не отвечало"
        else -> "Другая причина завершения"
    }

    fun summary(reason: Int, status: Int, timestampMs: Long, version: String): String = buildString {
        appendLine("HomeBabyMonitor $version")
        appendLine("Причина: ${label(reason)} (код $reason)")
        appendLine("Статус / сигнал: $status")
        appendLine("Время (UTC epoch ms): $timestampMs")
        appendLine("Проверка: внутренние сведения об аварийном завершении Android")
    }
}
