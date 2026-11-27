package dev.rail8.testkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The arguments of an ICU message, as the UI's texts are written: each argument's name and its type, one of {@link #NONE},
 * {@code number}, {@code date}, {@code time}, {@code plural}, {@code select} or {@code selectordinal}. A {@code plural}, a
 * {@code select} and a {@code selectordinal} hold sub-messages, one for each of their selectors, among them {@code other};
 * the arguments of the sub-messages are the message's too. A name used twice keeps the type of its first use.
 *
 * <p>The syntax read is ICU's: {@code {name}}, {@code {name, type}} and {@code {name, type, style}} for the simple types,
 * {@code {name, plural, offset:1 =0 {…} one {…} other {…}}}, {@code #} inside a plural's sub-message, and an apostrophe that
 * quotes a following {@code {}, {@code }}, {@code #} or {@code |} up to the next lone apostrophe, two of them standing for
 * one. Markup such as {@code <b>} is text. It reads only what a check of the arguments needs: a style is not looked into.
 */
final class IcuArguments {

    /** The type of an argument named alone, {@code {name}}. */
    static final String NONE = "none";

    private static final Set<String> SIMPLE = Set.of("number", "date", "time");
    private static final Set<String> CHOOSING = Set.of("plural", "select", "selectordinal");
    /** The deepest nesting of arguments read, so a message cannot exhaust the stack. */
    private static final int MAX_DEPTH = 32;

    private final String text;
    private final Map<String, String> found = new LinkedHashMap<>();
    private int at;

    private IcuArguments(String text) {
        this.text = text;
    }

    /**
     * The arguments of {@code message}, by name, each with its type, in the order they first appear.
     *
     * @throws IllegalArgumentException naming the place where {@code message} is no ICU message
     */
    static Map<String, String> of(String message) {
        IcuArguments reader = new IcuArguments(message);
        reader.message(0, false);
        if (reader.at < message.length()) throw reader.refused("a '}' that closes nothing");
        return Collections.unmodifiableMap(reader.found);
    }

    /** Reads text and arguments up to an unquoted {@code }} or the end; {@code #} is a placeholder when {@code plural}. */
    private void message(int depth, boolean plural) {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\'') {
                quote(plural);
            } else if (c == '{') {
                argument(depth + 1);
            } else if (c == '}') {
                return;
            } else {
                at++;
            }
        }
    }

    /** At an apostrophe: two stand for one; one before a syntax character quotes up to the next lone one; else it is text. */
    private void quote(boolean plural) {
        if (at + 1 < text.length() && text.charAt(at + 1) == '\'') {
            at += 2;
            return;
        }
        char next = at + 1 < text.length() ? text.charAt(at + 1) : 0;
        if (next != '{' && next != '}' && next != '|' && !(plural && next == '#')) {
            at++;
            return;
        }
        at++;
        while (at < text.length()) {
            if (text.charAt(at) == '\'') {
                if (at + 1 < text.length() && text.charAt(at + 1) == '\'') {
                    at += 2;
                    continue;
                }
                at++;
                return;
            }
            at++;
        }
    }

    /** At a {@code {}: reads one argument through its closing {@code }}. */
    private void argument(int depth) {
        if (depth > MAX_DEPTH) throw refused("arguments nested deeper than " + MAX_DEPTH);
        at++;
        space();
        int start = at;
        while (at < text.length() && !Character.isWhitespace(text.charAt(at)) && ",{}'#".indexOf(text.charAt(at)) < 0) at++;
        String name = text.substring(start, at);
        if (name.isEmpty()) throw refused("an argument without a name");
        space();
        if (peek() == '}') {
            found.putIfAbsent(name, NONE);
            at++;
            return;
        }
        expect(',');
        space();
        start = at;
        while (at < text.length() && Character.isLetter(text.charAt(at))) at++;
        String type = text.substring(start, at);
        space();
        if (SIMPLE.contains(type)) {
            found.putIfAbsent(name, type);
            if (peek() == ',') {
                at++;
                style();
            }
            expect('}');
        } else if (CHOOSING.contains(type)) {
            found.putIfAbsent(name, type);
            expect(',');
            choices(depth, !"select".equals(type));
        } else {
            throw refused("the argument '" + name + "' has the type '" + type + "', which is none of none, number, date, time, plural, select, selectordinal");
        }
    }

    /** A simple argument's style, up to its closing {@code }}, which is left to read. */
    private void style() {
        while (at < text.length() && text.charAt(at) != '}') {
            if (text.charAt(at) == '{') throw refused("a '{' in a style");
            if (text.charAt(at) == '\'') quote(false);
            else at++;
        }
    }

    /** The selectors and sub-messages of a plural, select or selectordinal, through its closing {@code }}. */
    private void choices(int depth, boolean plural) {
        space();
        if (plural && text.startsWith("offset:", at)) {
            at += "offset:".length();
            int start = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            if (at == start) throw refused("an offset without a number");
            space();
        }
        boolean other = false;
        boolean any = false;
        while (peek() != '}') {
            int start = at;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at)) && "{}".indexOf(text.charAt(at)) < 0) at++;
            String selector = text.substring(start, at);
            if (selector.isEmpty()) throw refused("a sub-message without a selector");
            if (!selector.matches(plural ? "=-?\\d+(\\.\\d+)?|[a-z]+" : "[A-Za-z0-9_-]+")) throw refused("the selector '" + selector + "'");
            other |= selector.equals("other");
            any = true;
            space();
            expect('{');
            message(depth, plural);
            expect('}');
            space();
        }
        if (!any) throw refused("a choice without a sub-message");
        if (!other) throw refused("a choice without its sub-message 'other'");
        at++;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private char peek() {
        if (at >= text.length()) throw refused("the message ends inside an argument");
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) throw refused("'" + c + "' expected");
        at++;
    }

    private IllegalArgumentException refused(String why) {
        return new IllegalArgumentException("no ICU message: " + why + " at " + at);
    }
}
