package fr.buildtheearth.skywindow.translate;

import fr.buildtheearth.skywindow.brigadier.StringReader;
import fr.buildtheearth.skywindow.brigadier.arguments.ArgumentType;
import fr.buildtheearth.skywindow.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * Brigadier {@link ArgumentType}s that mirror how Minecraft's own command arguments consume text,
 * while reporting <b>which character ranges are Y coordinates</b>. This is the layer between the
 * vendored Mojang Brigadier parser (exact parse semantics: literals first, backtracking, ranges)
 * and SkyWindow's offset translation (exact Y token positions).
 *
 * <p>Width rules follow the protocol's argument types: positions consume three coordinate pieces
 * (the middle one is the Y), column/vec2/rotation consume two and carry no Y, numeric and string
 * arguments named like a Y shift their single token, greedy tail arguments eat the rest without
 * coordinates, and nested-command arguments are recursively parsed against the root tree.</p>
 */
public final class CommandSpans {

    /** A Y character range in the parsed text, [start, end). */
    public record Span(int start, int end) {
    }

    /** Set after the root dispatcher exists; nested-command arguments parse against it. */
    public static final class RootHolder {
        private Object dispatcher;
        private Object source;

        public void set(Object dispatcher, Object source) {
            this.dispatcher = dispatcher;
            this.source = source;
        }

        Object dispatcher() {
            return dispatcher;
        }

        Object source() {
            return source;
        }
    }

    public enum Kind {
        /** Three coordinate pieces; the middle one is an absolute-capable Y. */
        POSITION3,
        /** Two pieces (column x/z, vec2 x/z, rotation yaw/pitch): no Y at all. */
        POSITION2,
        /** One token, shifted when {@code yNamed}. */
        SINGLE,
        /** Greedy tail (message / greedy string): content, never coordinates. */
        GREEDY_TEXT,
        /** Nested command text (execute ... run): recursively parsed against the root tree. */
        NESTED_COMMAND,
    }

    private CommandSpans() {
    }

    public static ArgumentType<List<Span>> of(Kind kind, boolean yNamed, RootHolder root) {
        return new SpanArgument(kind, yNamed, root);
    }

    private static final class SpanArgument implements ArgumentType<List<Span>> {
        private final Kind kind;
        private final boolean yNamed;
        private final RootHolder root;

        private SpanArgument(Kind kind, boolean yNamed, RootHolder root) {
            this.kind = kind;
            this.yNamed = yNamed;
            this.root = root;
        }

        @Override
        public List<Span> parse(StringReader reader) throws CommandSyntaxException {
            switch (kind) {
                case POSITION3 -> {
                    String x = readPiece(reader);
                    int startY = reader.getCursor();
                    String y = readPiece(reader);
                    int endY = reader.getCursor();
                    readPiece(reader);
                    if (!isCoordinate(x) || !isCoordinate(y)) {
                        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                            .readerExpectedDouble().createWithContext(reader);
                    }
                    return List.of(new Span(startY, endY));
                }
                case POSITION2 -> {
                    readPiece(reader);
                    readPiece(reader);
                    return List.of();
                }
                case SINGLE -> {
                    int start = reader.getCursor();
                    readPiece(reader);
                    if (yNamed) {
                        return List.of(new Span(start, reader.getCursor()));
                    }
                    return List.of();
                }
                case GREEDY_TEXT -> {
                    reader.setCursor(reader.getTotalLength());
                    return List.of();
                }
                case NESTED_COMMAND -> {
                    int start = reader.getCursor();
                    String rest = reader.getRemaining();
                    reader.setCursor(reader.getTotalLength());
                    if (root == null || root.dispatcher() == null || rest.isEmpty()) {
                        return List.of();
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        var dispatcher = (fr.buildtheearth.skywindow.brigadier.CommandDispatcher<Object>) root.dispatcher();
                        fr.buildtheearth.skywindow.brigadier.ParseResults<Object> sub =
                            dispatcher.parse(rest, root.source());
                        if (sub.getReader().canRead()) {
                            return List.of(); // nested text did not parse whole: the server rejects it anyway
                        }
                        List<Span> spans = new ArrayList<>();
                        for (var entry : sub.getContext().getArguments().values()) {
                            Object value = entry.getResult();
                            if (value instanceof List<?> list) {
                                for (Object item : list) {
                                    if (item instanceof Span span) {
                                        spans.add(new Span(span.start() + start, span.end() + start));
                                    }
                                }
                            }
                        }
                        return spans;
                    } catch (RuntimeException e) {
                        return List.of(); // best-effort: never let a nested parse kill the outer one
                    }
                }
            }
            return Collections.emptyList();
        }

        private static String readPiece(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
                reader.skip();
            }
            String piece = reader.getString().substring(start, reader.getCursor());
            if (piece.isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                    .readerExpectedEndOfQuote().createWithContext(reader);
            }
            skipWhitespace(reader);
            return piece;
        }

        private static void skipWhitespace(StringReader reader) {
            while (reader.canRead() && Character.isWhitespace(reader.peek())) {
                reader.skip();
            }
        }
    }

    private static boolean isCoordinate(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        if (first == '~' || first == '^') {
            return true;
        }
        return CommandYRewrite.parseAbsoluteNumber(token) != null;
    }
}
