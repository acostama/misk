package misk.metrics

/*
 * Skeleton for the functionality of histograms
 */

interface Histogram {

    fun record(duration: Double, vararg labelValues: String)

    fun count(): Int
}