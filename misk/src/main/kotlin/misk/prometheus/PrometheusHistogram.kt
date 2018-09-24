package misk.prometheus

import misk.metrics.Histogram
import misk.metrics.count

class PrometheusHistogram: Histogram {
    lateinit var histogram: io.prometheus.client.Histogram
    var bucketCount: Int = 0

    override fun record(duration: Double, vararg labelValues: String) {
        var child: io.prometheus.client.Histogram.Child = histogram.labels(*labelValues)
        child.observe(duration)
        bucketCount = child.get().count
    }

    override fun count(): Int {
        return bucketCount
    }
}