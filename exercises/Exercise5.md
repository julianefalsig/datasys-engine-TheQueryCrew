# Exercise 5: Reading the Log, Owning the Code, Designing the Experiment

How to Build Data Systems – Fall 2026

In this exercise, the engine becomes a program that reads and analyzes its own log. You package it as a JAR with a launcher script. Then you `COPY` a snapshot of the live log file and use your own engine to analyze one session, one statement, and the failures. The rest of the week focuses on understanding the code, removing dead code, and designing the experiment for next week's report.

As a reminder:

- **Log messages never contain commas, double quotes, or newlines.**
- **The course uses exactly two log levels:** `LOGGER.debug` for normal records and `LOGGER.error` for failures. Do not use `info` or `warn`. Each line is either a normal record of work or a failure record.

## 1. Package the engine

Until now the engine ran through `mvn compile exec:java`, which starts Maven before it starts the engine and forces a second layer of quoting on every statement. Package the engine instead. Add to `pom.xml`, inside `<build>` and next to `<plugins>`, a fixed artifact name:

```xml
<finalName>engine</finalName>
```

Then add the shade plugin, which writes the classes and all dependencies into one runnable JAR:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.6.2</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>dk.itu.datasys.Engine</mainClass>
          </transformer>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The manifest transformer records the main class, so `java -jar` knows where to start. The services transformer merges the `META-INF/services` entries of all dependencies, which matters here: without it SLF4J finds no Log4j2 backend inside the merged JAR and the log stays empty. `mvn package` now produces `target/engine.jar`. Add `dependency-reduced-pom.xml`, which the plugin writes next to the POM, to `.gitignore`.

Then add a script named `engine` in the repository root:

```bash
#!/usr/bin/env bash
# Runs the packaged engine: ./engine -c "SELECT ..." or ./engine -f script.sql
exec java $ENGINE_JAVA_OPTS -jar "$(dirname "$0")/target/engine.jar" "$@"
```

Make it executable with `chmod +x engine`.

Try some examples:

```bash
./engine                                              # team name and usage
./engine -c "SELECT * FROM trips WHERE city = 'Odense'"
./engine -f script.sql > ours.csv
```

Update `Engine.main` and its usage text accordingly.

## 2. Ingest and analyze

First generate a log that is worth reading. Run a few scripts through the front door, including one failing statement. Then, through your own front door:

```sql
CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
                   logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/engine.log';
SELECT * FROM logs WHERE sessionId = '<an id you see in the file>';
SELECT * FROM logs WHERE statementNumber = 7;
SELECT * FROM logs WHERE logLevel = 'ERROR';
```

Log4j2 flushes each event as one complete line. As a result, a copy of the active file ends at a line boundary. Note that the second query returns statement 7 from every session in the file. The `sessionId` column distinguishes those sessions.

## 3. Understand and clean up the code

The course policy allows AI to write code, but the author must own that code. Show that ownership by tracing one statement through the engine. Set a breakpoint in the executor's statement loop. Run the front-door integration test or `Engine.main` on a script. Then single-step one `SELECT` with a `WHERE`, from text input to final output. The statement becomes an AST in the parser. Its names are bound against the schema. Its predicate becomes a partition list in the planner. Its rows then move through `open`, `next`, and `close` until the CSV reaches stdout. Understand every step.

Ownership also means removing code that the engine no longer needs. Make sure every method is reachable from the main entry point of your engine. If a method is only called from tests, you may delete both the method and its tests to keep the codebase small.

## 4. Design the experiment

The week 6 report requires one experiment on one performance dimension of the engine. Write the design in `docs/experiment-design.md` before you take the first measurement:

- **Question and x axis:** define the dimension you sweep. Examples are `maxRowsPerPartition` from 2 to 1024, table size from 1K to 1M rows, sorted versus shuffled input, or predicate selectivity from 0% to 100%.
- **Metric and y axis:** define what you measure from your own log. This could be the fraction of partitions read from the `decision=` lines, or the median `durationMs` from the summary lines. State the plot before you see it: what is on the x axis, what is on the y axis, and what one curve represents. Use a log scale on x if the values span multiple orders of magnitude.
- **Procedure:** describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.
- **Hypothesis:** state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.

## 5. Optional: hit the memory wall

The engine assumes that data fits in memory. Find out where that assumption lives. Generate a CSV that is clearly larger than a small heap, for example 2 million rows (about 50 MB) from a committed script, and use the launcher script to throttle the heap:

```bash
./engine -c "CREATE TABLE big (b_id LONG, b_name STRING, b_value DOUBLE)"
./engine -c "COPY big FROM 'big.csv'"                    # load once, default heap
ENGINE_JAVA_OPTS=-Xmx64m ./engine -c "SELECT * FROM big WHERE b_id = 42"
ENGINE_JAVA_OPTS=-Xmx64m ./engine -c "SELECT * FROM big WHERE b_id > 0" > /dev/null
ENGINE_JAVA_OPTS=-Xmx64m ./engine -c "COPY big FROM 'big.csv'"
```

- Which of the three throttled statements throw `java.lang.OutOfMemoryError`, and which survives? The stack traces name the two culprits; explain both from the code. Which allocations grow with the input file, which grow with the result, and which are bounded by a partition?
- Check the log after a crash: did the failure leave an `ERROR` line, or did the JVM die before the engine could keep its own logging promise?
- Write a short analysis of what would have to change for the engine to handle larger-than-memory data sets.

## 6. Required tests (JUnit 6)

1. Integration: after a failing statement, `logs/engine.log` contains an `ERROR` line for it.

## 7. Cut release v0.5

```bash
git checkout main && git pull
git tag -a v0.5 -m "Exercise 5: packaging, log ingestion, and code cleanup"
git push origin v0.5
```

This creates a tag that the teaching assistant will check out to validate your project state.

## Definition of done

- [ ] `mvn package` produces `target/engine.jar`; the executable `engine` script runs `-c` and `-f` and is committed.
- [ ] `logs` table created, a snapshot of `engine.log` ingested, and the session, statement, and error analyses run through your own engine.
- [ ] One statement traced end to end in the debugger by every team member.
- [ ] Methods without engine callers deleted and associated tests deleted.
- [ ] `docs/experiment-design.md` merged.
- [ ] All required tests green in CI; PRs reviewed.
- [ ] Tag `v0.5` is pushed.
- [ ] Low-heap runs done and larger-than-memory analysis written (optional).

## Outlook

Week 6 ships Part 1. You will run your experiment and write up the report for Project 1.