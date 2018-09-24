package misk.metrics

interface HistogramRegistry {

     fun newHistogram(
             name: String,
             help: String,
             labelNames: List<String> = listOf(),
             buckets: DoubleArray?
     ): Histogram
}