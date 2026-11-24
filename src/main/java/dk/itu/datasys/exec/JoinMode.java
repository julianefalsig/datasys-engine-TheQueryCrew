package dk.itu.datasys.exec;

/** {@link #OPTIMIZED} is the default. {@link #SYNTAX} is week 10: join in {@code FROM}-list order. */
public enum JoinMode {
    OPTIMIZED,
    SYNTAX;

    public String wireName() {
        return name().toLowerCase();
    }

    public static JoinMode parse(String text) {
        for (JoinMode mode : values()) {
            if (mode.wireName().equals(text)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("-j needs optimized or syntax");
    }
}
