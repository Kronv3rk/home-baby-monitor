package com.homebabymonitor.core

enum class SleepSound(val title: String) {
    OFF("Выключено"),
    LULLABY("Колыбельная"),
    MUSIC_BOX("Шкатулка"),
    RAIN("Шум дождя"),
    OCEAN("Морской прибой"),
    WIND("Тихий ветер"),
    WHITE_NOISE("Белый шум"),
    SOFT_NOISE("Мягкий шум"),
    HEARTBEAT("Сердцебиение"),
    LULLABY_2("Вторая колыбельная"),
    BELLS("Тихие колокольчики");

    val melody: Boolean get() = this in setOf(LULLABY, MUSIC_BOX, LULLABY_2, BELLS)

    companion object {
        fun fromWire(name: String): SleepSound? = entries.firstOrNull { it.name == name }
    }
}

data class SleepCommand(val sound: SleepSound, val minutes: Int, val volume: Int) {
    fun encode(): String = "MUSIC:${sound.name}:$minutes:$volume"
    companion object {
        fun parse(text: String): SleepCommand? {
            val parts = text.split(':')
            if (parts.size != 4 || parts[0] != "MUSIC") return null
            val sound = SleepSound.fromWire(parts[1]) ?: return null
            val minutes = parts[2].toIntOrNull()?.takeIf { it in setOf(10, 30, 60) } ?: return null
            val volume = parts[3].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            return SleepCommand(sound, minutes, volume)
        }
    }
}
