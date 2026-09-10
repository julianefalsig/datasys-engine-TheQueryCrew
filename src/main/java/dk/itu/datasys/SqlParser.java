package dk.itu.datasys;

import dk.itu.datasys.sql.SqlAstBuilder;
import dk.itu.datasys.sql.SqlLexer;
import dk.itu.datasys.sql.Statement;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SqlParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);
    private static final ThrowingErrorListener THROWING = new ThrowingErrorListener();

    public List<Statement> parse(String sqlText) {
        long startedNanos = System.nanoTime();
        try {
            SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
            lexer.removeErrorListeners();
            lexer.addErrorListener(THROWING);

            dk.itu.datasys.sql.SqlParser parser = new dk.itu.datasys.sql.SqlParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(THROWING);

            @SuppressWarnings("unchecked")
            List<Statement> statements = (List<Statement>) new SqlAstBuilder().visit(parser.script());
            LOGGER.debug("statements={} durationMs={}", statements.size(), durationMs(startedNanos));
            return statements;
        } catch (SqlParseException e) {
            LOGGER.error("failed line={} col={} durationMs={}", e.line(), e.column(), durationMs(startedNanos));
            throw e;
        }
    }

    private static long durationMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
            throw new SqlParseException(msg, line, charPositionInLine, e);
        }
    }
}
