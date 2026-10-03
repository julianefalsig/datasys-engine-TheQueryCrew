This Markdown documents the decisions made in regards to the experiment design for the report for part 1 of the engine

## Question and x axis: define the dimension you sweep. Examples are maxRowsPerPartition from 2 to 1024, table size from 1K to 1M rows, sorted versus shuffled input, or predicate selectivity from 0% to 100%.

## Metric and y axis: define what you measure from your own log. This could be the fraction of partitions read from the decision= lines, or the median durationMs from the summary lines. State the plot before you see it: what is on the x axis, what is on the y axis, and what one curve represents. Use a log scale on x if the values span multiple orders of magnitude.

## Procedure: describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.

## Hypothesis: state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.