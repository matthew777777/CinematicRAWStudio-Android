package com.matthew.crs

/** Opt-in wall-clock timings. Nested and concurrent stages must not be summed. */
object PipelineTiming {
    @Volatile var enabled = false
    private val samples = linkedMapOf<String, MutableList<Long>>()

    inline fun <T> measure(stage: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try { return block() } finally { record(stage, System.nanoTime() - start) }
    }

    @Synchronized fun record(stage: String, nanos: Long) {
        if (enabled) samples.getOrPut(stage) { mutableListOf() }.add(nanos)
    }

    @Synchronized fun reset() { samples.clear() }

    @Synchronized fun snapshot(): String = samples.entries.joinToString("\n") { (name, values) ->
        val sorted = values.sorted()
        val p95 = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]
        "%s count=%d mean=%.3f p95=%.3f max=%.3f ms".format(java.util.Locale.US,
            name, values.size, values.average() / 1e6, p95 / 1e6, sorted.last() / 1e6)
    }
}
