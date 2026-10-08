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

The plot will then be maxRowsPerPartition on the x-axis, throughput on the y-axis and 1 series for each table size tested. The x-axis will have a log-scale to fit the data size exponential increase pattern. This will be noted clearly in the caption in the report or on the legend of the plot.

## Procedure: describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.

We generate one shuffled CSV per table size, same schema and same value distributions on the columns we filter on, so a predicate with a fixed result-fraction stays comparable across the grid. Each cell is then: start an engine with that `maxRowsPerPartition`, `CREATE` + `COPY` the matching CSV, then time only the `SELECT` (ingest stays out of the throughput number). Calculated by (note that we are looking into the predicate operator in particular when defining Rows-in and Rows-out from the log-lines & that executionMs comes from the log-lines as well and only times the execution itself (excluding parse, bind & plan)):

$$
T_{in} (r/Ms) = Rows_{in} / executionMs

T_{out} (r/Ms) = Rows_{out} / executionMs
$$

The `SELECT` statement has a predicate (as it otherwise does not make sense to look into pruning at all). The predicate is fixed, to avoid exponentially increasing the number of experiments needed in limited time.

We sweep the 8 × 8 grid above. If we have time we run each cell 6 times, drop the first as a cold run, and average the other 5;
When the 'masRowsPerPartition' grows larger than the table size (for the smaller tables) we will not produce any additional experiments as they would be redundant.
We report Median + min/max range for analyses.

We will note the machine, OS, and `java -version` (the project is Java 25) in the report. Heap stays the JVM default unless the 1.000.000-row `COPY` fails, in which case we bump it once and write that down.

We will run the experiment on one of our MacBooks and specify the the hardware in the report.

## Hypothesis: state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.

Because the tables are shuffled, we expect pruning to die as partitions get bigger: at `maxRowsPerPartition = 8` a selective predicate should skip a large share of files and at 32.768 we expect almost every partition's min/max to cover the constant, so we choose to read vast majority of fractions.
Throughput vs partition size should therefore rise at first (fewer tiny files) and then flatten or drop once pruning stops helping, and that drop should be most visible on the 500.000 / 1.000.000 series, not on the 500-row ones.