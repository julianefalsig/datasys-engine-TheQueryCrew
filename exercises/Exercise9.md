# Exercise 9: Project 2 Report and Demo

How to Build Data Systems – Fall 2026

Project 2 hand-in is on Nov 12. There are no new engine features in this exercise: you generate the evidence and write the report.

## 1. Generate a conflict

Produce evidence of one conflict. Run the two-writer test and capture it, either as a screenshot of the run or as the log lines of both writers. The log should already tell the whole story: the `threadId` column separates the writers, `version=N` shows whose commit won, and the loser's `ERROR` line shows the abort.

## 2. The report (3–5 pages)

Write for a reader who knows the course material but has never seen your code.

- **Versioning (~1 page):** what a version is in your metadata: where the counter lives, what a commit does, and why the swap is atomic. Include one worked example: the catalog of a table before and after a `DELETE`, version N to N+1, as a diagram or an annotated excerpt of `catalog.json`.
- **Conflict detection (~1 page):** one conflict, end to end. The interleaving: who pinned which base version, who committed first. The evidence: the screenshot or log excerpt from Task 1, explained line by line. The outcome: why this is a conflict, who won, what the loser left behind, and what a rerun of the loser does. Name your resolution strategy, and state what a better strategy would gain and what it would cost to implement.
- **Reflections (~1–3 pages):** how the engine changed for Part 2, as four questions with a paragraph or two each.
  - *The storage layer:* what did concurrent writers cost you in the storage layer, and did any of that cost come as a surprise?
  - *ACID:* your language has no `BEGIN`, so one statement is one transaction. Which of the four ACID properties does a transaction have, which does it only approximate, and which does it not have at all?
  - *The retrofit:* which Part 1 decisions helped and which got in the way? How many places did versioning touch? Count the classes, and name the changes that were truly intrusive. Would designing versioning in from week 1 have been cheaper, or did AI assistance make the retrofit cheap enough that it did not matter?
  - *AI usage:* did your usage of AI differ from Project 1?

**AI policy for the report: do not let AI write the report for you.** Write at least the bullet points yourself, and let AI turn them into paragraphs. We want *your* analysis and *your* reflections, not AI's insights and not AI reflecting on itself.

## 3. Demo (optional, in class)

Five minutes, three steps, live in class. Live means commands typed into a terminal, not screenshots:

1. The two-writer test, run live, with the assertion that the loser aborted.
2. The conflict's log lines, pulled through your own engine: two thread ids, one winning version, one `ERROR` line.
3. One reflection: the insight about retrofitting transactions that you want the class to remember.

## Definition of done

- [ ] Conflict evidence generated from your own engine.
- [ ] Report of 3–5 pages, submitted via LearnIT.
- [ ] Demo ready (optional).