package misk.metrics

interface Histogram {

    fun record(duration: Double, vararg labelValues: String): Any

    fun count(): Int
}