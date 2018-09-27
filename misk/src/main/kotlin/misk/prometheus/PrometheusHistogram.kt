package misk.prometheus

import misk.metrics.Histogram

class PrometheusHistogram: Histogram {
    lateinit var histogram: io.prometheus.client.Histogram
    var bucketCount: Int = 0

    override fun record(duration: Double, vararg labelValues: String) {
        var child: io.prometheus.client.Histogram.Child = histogram.labels(*labelValues)
        child.observe(duration)
        bucketCount = child.get().buckets.max()?.toInt() ?: 0
    }

    override fun count(): Int {
        return bucketCount
    }
}