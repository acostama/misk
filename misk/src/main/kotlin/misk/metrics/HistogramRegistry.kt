package misk.metrics

/*
 * Skeleton to create a new histogram
 *
 * Implementation should register the histogram upon creation
 */

interface HistogramRegistry {

     fun newHistogram(
             name: String,
             help: String,
             labelNames: List<String> = listOf(),
             buckets: DoubleArray?
     ): Histogram
}