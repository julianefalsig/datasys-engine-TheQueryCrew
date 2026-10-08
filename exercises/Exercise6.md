# Exercise 6: Experiment, Report, and Demo

How to Build Data Systems – Fall 2026

Project 1 hand-in is on Oct 22. There are no new engine features in this exercise: you run the experiment you designed in week 5, turn its results into the report's chart, and write the report.

## 1. Run the experiment

Run the experiment exactly as your `docs/experiment-design.md` states it. Requirements for the run:

- Generate the data with a script and a fixed seed, committed to the repository, so that any teammate can regenerate the same files and rerun the sweep.
- State the conditions once: machine, OS, JVM version, heap size, and the commit at which you measured.
- Take your engine's numbers from its own log (optionally ingested and filtered with the engine itself).

Plot the results as the chart your design promised: both axes labeled with units, a caption that says what one bar or curve represents, and a log scale where the values span orders of magnitude. In the report, describe the experimental setup, present the measurements, and state whether the outcome matched your hypotheses.

## 2. The report (5–7 pages)

Write for a reader who knows the course material but has never seen your code.

- **Architecture (~2–3 pages):** the architecture as one diagram (main components and how they interact with each other), then your design decisions (file layout, statistics placement, catalog layout): what you chose, why, and what the choice may have cost you later.
- **Measurements (~2–3 pages):** the experiment. Quote the hypothesis, and state whether it was confirmed or refuted. Then the setup, the chart itself, and a discussion that explains the measurements.
- **Reflections (~1 page):** what you would do differently and why, and one bug that mattered: how it got in, how you found it, and what catches it now.
- **AI-usage reflection (part of reflections):** which tools you used, where they helped, where they misled, and one concrete example of generated code you rejected or rewrote, and why.

**AI policy for the report: do not let AI write the report for you.** Write at least the bullet points yourself, and let AI turn them into paragraphs. We want *your* insights, *your* benchmark analysis, and *your* reflections, not AI's insights and not AI reflecting on itself.

## 3. Demo (optional, in class)

Five minutes, three steps, live in class. Live means commands typed into a terminal, not screenshots:

1. One part of your experiment, run through your engine, for example `./engine -f experiment1.sql`, with the output visible.
2. A summary of the experiment as it appears in your report: the chart and the verdict on the hypothesis.
3. A discussion of your reflections: pick the one insight you want the class to remember.

## 4. Cut release v0.6

```bash
git checkout main && git pull
git tag -a v0.6 -m "Part 1: storage, SQL front end, Volcano pipeline, dogfooding"
git push origin v0.6
```

This is the state of the engine that the report describes, and the tag the teaching assistant checks out to validate Part 1.

## Definition of done

- [ ] Experiment run as designed: script and seed committed, at least 5 executions per point, median and spread reported, conditions stated.
- [ ] Chart created, with labeled axes and units.
- [ ] Report of 5–7 pages, submitted via LearnIT.
- [ ] Tag `v0.6` pushed, green in CI.
- [ ] Demo ready (optional).