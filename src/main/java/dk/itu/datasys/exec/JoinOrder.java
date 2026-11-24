package dk.itu.datasys.exec;

import dk.itu.datasys.sql.ColumnResolver;
import dk.itu.datasys.sql.JoinPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The join sequence. {@code syntax} follows the FROM list and always builds the tables already
 * joined. {@code greedy} starts at the predicate with the smallest estimate and then adds the
 * connected table with the smallest estimate. The smaller input builds.
 */
final class JoinOrder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JoinOrder.class);

    record Card(String name, double rows, java.util.Map<String, Long> ndv) {
    }

    /** Build tables, then probe tables, in the row order this join emits before the final permute. */
    record Join(List<String> buildTables, List<String> probeTables, String buildColumn, String probeColumn,
            double estRows) {
    }

    private JoinOrder() {
    }

    static List<Join> syntax(List<Card> fromOrder, List<JoinPredicate> predicates, ColumnResolver columns) {
        List<Join> joins = new ArrayList<>();
        Group group = new Group(List.of(fromOrder.getFirst().name()), fromOrder.getFirst().rows());
        for (int i = 1; i < fromOrder.size(); i++) {
            Card next = fromOrder.get(i);
            JoinPredicate predicate = onePredicate(predicatesBetween(group.tables, next.name(), predicates, columns),
                    next.name(), group.tables, JoinMode.SYNTAX);
            double estRows = estimate(group, next, predicate, fromOrder, columns);
            joins.add(new Join(group.tables, List.of(next.name()),
                    columnOn(predicate, group.tables, columns), columnOn(predicate, List.of(next.name()), columns),
                    estRows));
            group = new Group(concat(group.tables, next.name()), estRows);
        }
        logChosen(JoinMode.SYNTAX, group.tables);
        return joins;
    }

    static List<Join> greedy(List<Card> fromOrder, List<JoinPredicate> predicates, ColumnResolver columns) {
        Candidate first = null;
        List<Candidate> opening = new ArrayList<>();
        for (int i = 0; i < fromOrder.size(); i++) {
            for (int j = i + 1; j < fromOrder.size(); j++) {
                Card left = fromOrder.get(i);
                Card right = fromOrder.get(j);
                List<JoinPredicate> between = predicatesBetween(List.of(left.name()), right.name(), predicates, columns);
                if (between.isEmpty()) {
                    continue;
                }
                JoinPredicate predicate = onePredicate(between, right.name(), List.of(left.name()),
                        JoinMode.OPTIMIZED);
                double estRows = Estimator.joinRows(left.rows(), right.rows(),
                        ndv(left, columnOn(predicate, List.of(left.name()), columns)),
                        ndv(right, columnOn(predicate, List.of(right.name()), columns)));
                Candidate candidate = new Candidate(new Group(List.of(left.name()), left.rows()), right, predicate,
                        estRows);
                opening.add(candidate);
                if (first == null || prefer(candidate, first, fromOrder)) {
                    first = candidate;
                }
            }
        }
        if (first == null) {
            throw crossProduct(JoinMode.OPTIMIZED, fromOrder.get(1).name(), List.of(fromOrder.getFirst().name()));
        }
        logCandidates(opening, first);
        JoinPredicate predicate = first.predicate;
        Card left = card(fromOrder, first.group.tables.getFirst());
        Card right = first.added;
        boolean leftBuilds = builds(left.rows(), right.rows(), indexOf(fromOrder, left.name()),
                indexOf(fromOrder, right.name()));
        Card build = leftBuilds ? left : right;
        Card probe = leftBuilds ? right : left;
        Join openingJoin = new Join(List.of(build.name()), List.of(probe.name()),
                columnOn(predicate, List.of(build.name()), columns),
                columnOn(predicate, List.of(probe.name()), columns), first.estRows);
        List<Join> joins = new ArrayList<>();
        joins.add(openingJoin);
        // Physical row order follows whoever builds. The log follows who was joined, so -j syntax can replay it.
        List<String> joinedOrder = new ArrayList<>();
        joinedOrder.add(build.name());
        joinedOrder.add(probe.name());
        Group group = new Group(concat(openingJoin.buildTables, probe.name()), first.estRows);

        while (group.tables.size() < fromOrder.size()) {
            Candidate best = null;
            List<Candidate> round = new ArrayList<>();
            for (Card next : fromOrder) {
                if (group.tables.contains(next.name())) {
                    continue;
                }
                List<JoinPredicate> between = predicatesBetween(group.tables, next.name(), predicates, columns);
                if (between.isEmpty()) {
                    continue;
                }
                JoinPredicate nextPredicate = onePredicate(between, next.name(), group.tables, JoinMode.OPTIMIZED);
                double estRows = estimate(group, next, nextPredicate, fromOrder, columns);
                Candidate candidate = new Candidate(group, next, nextPredicate, estRows);
                round.add(candidate);
                if (best == null || prefer(candidate, best, fromOrder)) {
                    best = candidate;
                }
            }
            if (best == null) {
                throw crossProduct(JoinMode.OPTIMIZED, unconnected(fromOrder, group.tables), group.tables);
            }
            logCandidates(round, best);
            boolean addedBuilds = builds(best.added.rows(), group.rows, indexOf(fromOrder, best.added.name()),
                    indexOf(fromOrder, group.tables.getFirst()));
            String addedColumn = columnOn(best.predicate, List.of(best.added.name()), columns);
            String groupColumn = columnOn(best.predicate, group.tables, columns);
            Join join = addedBuilds
                    ? new Join(List.of(best.added.name()), group.tables, addedColumn, groupColumn, best.estRows)
                    : new Join(group.tables, List.of(best.added.name()), groupColumn, addedColumn, best.estRows);
            joins.add(join);
            joinedOrder.add(best.added.name());
            group = new Group(concat(join.buildTables, join.probeTables), best.estRows);
        }
        logChosen(JoinMode.OPTIMIZED, joinedOrder);
        return joins;
    }

    private static double estimate(Group group, Card added, JoinPredicate predicate, List<Card> fromOrder,
            ColumnResolver columns) {
        String groupColumn = columnOn(predicate, group.tables, columns);
        String addedColumn = columnOn(predicate, List.of(added.name()), columns);
        Card owner = card(fromOrder, columns.resolve(groupColumn).tableName());
        return Estimator.joinRows(group.rows, added.rows(), ndv(owner, groupColumn), ndv(added, addedColumn));
    }

    private static Long ndv(Card card, String column) {
        return card.ndv().get(column);
    }

    /** Smaller estimate wins. A tie keeps the table earlier in the FROM list. */
    private static boolean prefer(Candidate candidate, Candidate best, List<Card> fromOrder) {
        if (candidate.estRows != best.estRows) {
            return candidate.estRows < best.estRows;
        }
        return indexOf(fromOrder, candidate.added.name()) < indexOf(fromOrder, best.added.name());
    }

    /** The smaller side builds. Equal sizes keep the earlier FROM table on the build side. */
    private static boolean builds(double leftRows, double rightRows, int leftIndex, int rightIndex) {
        if (leftRows != rightRows) {
            return leftRows < rightRows;
        }
        return leftIndex <= rightIndex;
    }

    private static List<JoinPredicate> predicatesBetween(List<String> joined, String table,
            List<JoinPredicate> predicates, ColumnResolver columns) {
        List<JoinPredicate> found = new ArrayList<>();
        for (JoinPredicate predicate : predicates) {
            String left = columns.resolve(predicate.leftColumn()).tableName();
            String right = columns.resolve(predicate.rightColumn()).tableName();
            boolean leftJoined = joined.contains(left);
            boolean rightJoined = joined.contains(right);
            boolean leftNew = left.equals(table);
            boolean rightNew = right.equals(table);
            if ((leftJoined && rightNew) || (rightJoined && leftNew)) {
                found.add(predicate);
            }
        }
        return found;
    }

    private static String columnOn(JoinPredicate predicate, List<String> tables, ColumnResolver columns) {
        if (tables.contains(columns.resolve(predicate.leftColumn()).tableName())) {
            return predicate.leftColumn();
        }
        return predicate.rightColumn();
    }

    private static JoinPredicate onePredicate(List<JoinPredicate> predicates, String added, List<String> joined,
            JoinMode mode) {
        if (predicates.isEmpty()) {
            throw crossProduct(mode, added, joined);
        }
        if (predicates.size() > 1) {
            throw new IllegalArgumentException(
                    "more than one join predicate connects " + added + " to " + joined
                            + " (" + predicates + "); this engine accepts one equi-join predicate per added table");
        }
        return predicates.getFirst();
    }

    private static IllegalArgumentException crossProduct(JoinMode mode, String added, List<String> joined) {
        String message = "no join predicate connects " + added + " to " + joined + "; that would be a cross product";
        if (mode == JoinMode.SYNTAX) {
            message += ", reorder the FROM list so each new table joins one already listed";
        }
        return new IllegalArgumentException(message);
    }

    private static String unconnected(List<Card> fromOrder, List<String> joined) {
        for (Card card : fromOrder) {
            if (!joined.contains(card.name())) {
                return card.name();
            }
        }
        return fromOrder.getLast().name();
    }

    private static Card card(List<Card> cards, String name) {
        for (Card card : cards) {
            if (card.name().equals(name)) {
                return card;
            }
        }
        throw new IllegalArgumentException("unknown table: " + name);
    }

    private static int indexOf(List<Card> cards, String name) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).name().equals(name)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static List<String> concat(List<String> tables, String added) {
        List<String> names = new ArrayList<>(tables);
        names.add(added);
        return List.copyOf(names);
    }

    private static List<String> concat(List<String> build, List<String> probe) {
        List<String> names = new ArrayList<>(build);
        names.addAll(probe);
        return List.copyOf(names);
    }

    private static void logCandidates(List<Candidate> candidates, Candidate picked) {
        for (Candidate candidate : candidates) {
            LOGGER.debug("op=joinOrder mode=optimized candidate={} estRows={} decision={}",
                    csvSafe(String.join("+", candidate.group.tables) + "+" + candidate.added.name()),
                    formatRows(candidate.estRows), candidate == picked ? "PICK" : "SKIP");
        }
    }

    private static void logChosen(JoinMode mode, List<String> tables) {
        LOGGER.debug("statement=SELECT joinOrder={} mode={}", csvSafe(String.join(",", tables)), mode.wireName());
    }

    private static String formatRows(double rows) {
        if (Double.isFinite(rows) && rows == Math.rint(rows)) {
            return Long.toString((long) rows);
        }
        return Double.toString(rows);
    }

    private static String csvSafe(String value) {
        return value.replace(',', ';').replaceAll("\\s+", " ");
    }

    private record Group(List<String> tables, double rows) {
    }

    private record Candidate(Group group, Card added, JoinPredicate predicate, double estRows) {
    }
}
