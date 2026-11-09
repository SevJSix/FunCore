package me.sevj6.funCore.game;

/**
 * A setting that only one game uses, e.g. Bridge Duels' winning score. Declared on the {@link GameType}, stored under
 * {@code options:} in {@code games/<id>.yml}, and changed with {@code /game config <game> option <key> <value>}.
 *
 * @param key          lowercase, e.g. {@code "winning-score"}
 * @param defaultValue an {@code Integer}, {@code Double}, {@code Boolean} or {@code String}; also decides the type
 * @param min          lowest allowed number (ignored for booleans and strings)
 * @param max          highest allowed number
 * @param description  shown in {@code /game config}
 * @param check        strings only, optional: checks a value (throwing {@link IllegalArgumentException} with a message
 *                     for the player) and returns it tidied up
 */
public record GameOption(String key, Object defaultValue, double min, double max, String description,
                         java.util.function.@org.jetbrains.annotations.Nullable UnaryOperator<String> check) {

    public GameOption(String key, Object defaultValue, double min, double max, String description) {
        this(key, defaultValue, min, max, description, null);
    }

    public GameOption {
        if (!key.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid option key: " + key);
        if (!(defaultValue instanceof Integer || defaultValue instanceof Double || defaultValue instanceof Boolean
                || defaultValue instanceof String)) {
            throw new IllegalArgumentException("Option " + key + " must default to an Integer, Double, Boolean or String");
        }
    }

    /**
     * Parses and checks a value typed in a command or read from a file.
     *
     * @throws IllegalArgumentException with a message for the player if it's invalid
     */
    public Object parse(Object raw) {
        String text = String.valueOf(raw).trim();
        try {
            return switch (defaultValue) {
                case Integer ignored -> checkRange(raw instanceof Number n ? n.intValue() : Integer.parseInt(text));
                case Double ignored -> checkRange(raw instanceof Number n ? n.doubleValue() : Double.parseDouble(text));
                case Boolean ignored -> {
                    if (raw instanceof Boolean b) yield b;
                    if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) yield Boolean.parseBoolean(text);
                    throw new IllegalArgumentException(key + " is true or false.");
                }
                default -> check == null ? text : check.apply(text);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " needs a " + (defaultValue instanceof Integer ? "whole number" : "number") + ".");
        }
    }

    private <N extends Number> N checkRange(N value) {
        if (value.doubleValue() < min || value.doubleValue() > max) {
            throw new IllegalArgumentException(key + " must be between " + format(min) + " and " + format(max) + ".");
        }
        return value;
    }

    private String format(double value) {
        return defaultValue instanceof Integer ? String.valueOf((long) value) : String.valueOf(value);
    }
}
