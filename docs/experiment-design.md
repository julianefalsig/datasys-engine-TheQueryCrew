This Markdown documents the decisions made in regards to the experiment design for the report for part 1 of the engine

## Question and x axis: define the dimension you sweep. Examples are maxRowsPerPartition from 2 to 1024, table size from 1K to 1M rows, sorted versus shuffled input, or predicate selectivity from 0% to 100%.

We want to investigate the performance of our engine across 2 axes, creating a "matrix" / overlapping series type experiment. We are in particular interested in investigating (1) the impact of 'maxRowsPerPartition' on performance and (2) the impact of table size to investigate scalability. The interaction between these two is also super interesting to investigate, so we aim to grid search across the following parameter values:
- maxRowsPerPartition: [8, 64, 512, 2.048, 4.096, 8.192, 16.384, 32.768]
- table size (rows): [500, 1.000, 5.000, 10.000, 50.000, 100.000, 500.000, 1.000.000]

This resulting experiment in a total of 8 * 8 = 64 runs.

Tables should be randomly generated but should have matching distributions for relevant fields (that we intend to filter on, to keep experiments comparable)

## Metric and y axis: define what you measure from your own log. This could be the fraction of partitions read from the decision= lines, or the median durationMs from the summary lines. State the plot before you see it: what is on the x axis, what is on the y axis, and what one curve represents. Use a log scale on x if the values span multiple orders of magnitude.

We think our main metric should be through-put of a specific SELECT-statement with a predicate that has the same fraction of result rows compared to the full table on each experiment. I.e. we want to measure tuples / sec based on the total runtime from supplying the select statement to rows returned, normalized to rates to ensure different table sizes are comparable.

If time allows it, we also think it would be cool to run each experiment 2-3 times to get and average performance.
To enrich our analyses, we think we may also use statistics such as number of partitions pruned.

The plot will then be maxRowsPerPartition on the x-axis, throughput on the y-axis and 1 series for each table size tested.

## Procedure: describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.

## Hypothesis: state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.