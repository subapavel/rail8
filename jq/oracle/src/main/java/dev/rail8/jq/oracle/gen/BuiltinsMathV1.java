package dev.rail8.jq.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.oracle.diff.CaseFormat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code builtins-math-v1} Generator: a function from a Seed to one differential case, {@code
 * gen/builtins-math-v1/<seed>}, that calls one math builtin on drawn numbers and on the other kinds, each call under
 * {@code try … catch .}, with stdin {@code null} and {@code -c}. Like {@link CoreV1} it uses only the JDK and {@link
 * SplitMix64}, with the seed as the state and integer draws only. It is frozen at the first merge of {@code
 * diff/gen/builtins-math-v1.rec}: a change to any output byte makes it {@code builtins-math-v2}.
 *
 * <p>An exact builtin's result is printed raw. A computed builtin's result goes through {@link #PRINTER}, which is
 * made of exact functions only: {@code [logb, (significand * 4294967296 | rint)]} when it is normal, {@code [class,
 * copysign(1; .)]} for a zero, a subnormal and an infinity, and {@code "nan"} for NaN. So a computed result is checked
 * to its top 32 bits of significand, its exponent and its sign, never to its last digits, which differ from jq's
 * by design. The Bessel and gamma builtins and the path builtins are never called.
 *
 * <table>
 *   <caption>builtins-math-v1</caption>
 *   <tr><th>parameter</th><th>value</th></tr>
 *   <tr><td>seeds</td><td>[0, {@value #TO}), {@value #PER_BUILTIN} per builtin: seed {@code s} calls {@link #BUILTINS}
 *       at {@code s % }{@value #COUNT}, and {@code i = s / }{@value #COUNT} is its round</td></tr>
 *   <tr><td>form</td><td>a builtin of arity 0: {@code x1, …, xk | try f catch .}. Of arity 2 or 3, round {@code i}
 *       even: {@code [a1, b1], …, [ak, bk] | . as [$a, $b] | try f($a; $b) catch .}, one {@code try} per call; round
 *       odd: {@code try f(A; B) catch .} with each argument a comma of 1–2 values, so jq's order over the
 *       arguments' results shows. k in 1..4. A computed builtin's call is {@code try (… | p) catch .} after {@code
 *       def p: …;}</td></tr>
 *   <tr><td>values</td><td>an edge 35, a double from random bits 20 (sign, an exponent field in 0..2046, 52 random
 *       bits, as {@link Double#toString(double)} spells it), a multiple of 1/8 in −8..8 35, another kind 10. In the
 *       rounds {@code i} with {@code i / 2} odd, one argument of the first call is another kind for sure, so half of
 *       each builtin's seeds give its error text</td></tr>
 *   <tr><td>pools</td><td>edges {@link #EDGES}: the zeros, ±1, halves, the subnormal and normal limits, the largest
 *       double, 2^52 + 1, a literal beyond a double, exponents that leave int and long, the infinities and the NaNs;
 *       other kinds {@link #KINDS}</td></tr>
 * </table>
 */
public final class BuiltinsMathV1 {

  /** The Generator's name: the family is {@code gen/builtins-math-v1}. */
  public static final String NAME = "builtins-math-v1";

  public static final String FAMILY = "gen/" + NAME;

  /** One math builtin: its name, its number of arguments, and whether IEEE 754 or C fixes every bit of its result. */
  public record Builtin(String name, int arity, boolean exact) {}

  /** The 25 exact math builtins and the 26 computed ones that jq 1.8.2 has, without the Bessel and gamma families. */
  public static final List<Builtin> BUILTINS =
      List.of(
          exact("ceil", 0), exact("fabs", 0), exact("floor", 0), exact("logb", 0), exact("nearbyint", 0),
          exact("rint", 0), exact("round", 0), exact("significand", 0), exact("sqrt", 0), exact("trunc", 0),
          exact("frexp", 0), exact("modf", 0), exact("copysign", 2), exact("drem", 2), exact("fdim", 2),
          exact("fmax", 2), exact("fmin", 2), exact("fmod", 2), exact("ldexp", 2), exact("nextafter", 2),
          exact("nexttoward", 2), exact("remainder", 2), exact("scalb", 2), exact("scalbln", 2), exact("fma", 3),
          computed("acos", 0), computed("acosh", 0), computed("asin", 0), computed("asinh", 0), computed("atan", 0),
          computed("atanh", 0), computed("cbrt", 0), computed("cos", 0), computed("cosh", 0), computed("erf", 0),
          computed("erfc", 0), computed("exp", 0), computed("exp10", 0), computed("exp2", 0), computed("expm1", 0),
          computed("log", 0), computed("log10", 0), computed("log1p", 0), computed("log2", 0), computed("sin", 0),
          computed("sinh", 0), computed("tan", 0), computed("tanh", 0), computed("atan2", 2), computed("hypot", 2),
          computed("pow", 2));

  public static final int COUNT = 51;

  public static final int PER_BUILTIN = 8;

  /** The recorded seed range, [{@value #FROM}, {@value #TO}). */
  public static final long FROM = 0;

  public static final long TO = (long) COUNT * PER_BUILTIN;

  /** The printer of a computed result, defined before the body of a computed builtin's filter. */
  public static final String PRINTER =
      "def p: if isnan then \"nan\" elif isinfinite then [\"infinite\", copysign(1; .)] elif . == 0 then [\"zero\", copysign(1; .)]"
          + " elif isnormal then [logb, (significand * 4294967296 | rint)] else [\"subnormal\", copysign(1; .)] end; ";

  /** The edge values as written in a filter; {@code (0 * -1)} is −0, since the literal {@code -0} is +0. */
  public static final List<String> EDGES =
      List.of(
          "0", "(0 * -1)", "1", "-1", "0.5", "-0.5", "1.5", "2.5", "-2.5", "3.7", "-3.7", "1e300", "-1e300", "5e-324",
          "-5e-324", "2.225073858507201e-308", "2.2250738585072014e-308", "1.7976931348623157e308",
          "-1.7976931348623157e308", "4503599627370497", "100000000000000000001", "2.7", "1e10", "1e19", "-1e19", "1024",
          "-1075", "infinite", "-infinite", "nan", "-nan");

  /** The values of the other kinds, as written in a filter. */
  public static final List<String> KINDS = List.of("null", "true", "false", "\"1\"", "\"a\"", "[1]", "[]", "{\"a\":1}");

  private static final List<String> VARIABLES = List.of("$a", "$b", "$c");

  /**
   * One generated case.
   *
   * @param builtin the builtin every call of the filter calls
   * @param filter the filter, run with {@code -c} on stdin {@code null}
   * @param values the values the filter passes to the builtin, as written in it, in the order they were drawn
   */
  public record Generated(long seed, Builtin builtin, String filter, List<String> values) {
    public Generated {
      values = List.copyOf(values);
    }


    /** The case block, LF-terminated: {@code case}, {@code arg -c}, {@code filter}, {@code stdin}, {@code end}. */
    public String block() {
      return "case " + FAMILY + '/' + seed + "\narg -c\nfilter " + CaseFormat.escape(filter.getBytes(UTF_8)) + "\nstdin null\\n\nend\n";
    }
  }

  private BuiltinsMathV1() {}

  private static Builtin exact(String name, int arity) {
    return new Builtin(name, arity, true);
  }

  private static Builtin computed(String name, int arity) {
    return new Builtin(name, arity, false);
  }

  /**
   * The text of {@code diff/gen/builtins-math-v1.cases} for the seeds [{@code from}, {@code to}): a header naming the
   * Generator, one {@code # dropped <seed> <reason>} line per Dropped seed, then the block of every other seed, each
   * after a blank line.
   *
   * @param dropped the reason ({@code rc3} or {@code timeout}) by Dropped seed, in seed order
   */
  public static String file(long from, long to, Map<Long, String> dropped) {
    StringBuilder b = new StringBuilder();
    b.append("# Generated cases of builtins-math-v1 (#312), seeds [").append(from).append(", ").append(to).append("): ");
    b.append("oracle/src/main/java/dev/rail8/jq/oracle/gen/BuiltinsMathV1.java.\n");
    b.append("# Written with builtins-math-v1.rec by scripts/oracle-record.sh --generate builtins-math-v1 ").append(from).append(' ').append(to);
    b.append(", never by hand; a seed that hits a deviation keeps its block and gains deviation and engine-* lines.\n");
    for (Map.Entry<Long, String> d : dropped.entrySet()) b.append("# dropped ").append(d.getKey()).append(' ').append(d.getValue()).append('\n');
    for (long seed = from; seed < to; seed++) {
      if (!dropped.containsKey(seed)) b.append('\n').append(generate(seed).block());
    }
    return b.toString();
  }

  /** The case of one seed, {@code seed >= 0}. */
  public static Generated generate(long seed) {
    SplitMix64 r = new SplitMix64(seed);
    Builtin f = BUILTINS.get((int) (seed % COUNT));
    long round = seed / COUNT;
    boolean otherKind = (round / 2) % 2 == 1;
    int calls = r.between(1, 4);
    List<String> drawn = new ArrayList<>();
    StringBuilder b = new StringBuilder(f.exact() ? "" : PRINTER);
    if (f.arity() == 0) {
      List<String> values = new ArrayList<>();
      for (int k = 0; k < calls; k++) values.add(k == 0 && otherKind ? kind(r) : value(r));
      drawn.addAll(values);
      b.append(String.join(", ", values)).append(" | ").append(guarded(f, f.name()));
    } else if (round % 2 == 0) {
      int forced = r.below(f.arity());
      List<String> tuples = new ArrayList<>();
      for (int k = 0; k < calls; k++) {
        List<String> args = new ArrayList<>();
        for (int a = 0; a < f.arity(); a++) args.add(k == 0 && otherKind && a == forced ? kind(r) : value(r));
        drawn.addAll(args);
        tuples.add("[" + String.join(", ", args) + "]");
      }
      List<String> vars = VARIABLES.subList(0, f.arity());
      b.append(String.join(", ", tuples)).append(" | . as [").append(String.join(", ", vars)).append("] | ");
      b.append(guarded(f, f.name() + "(" + String.join("; ", vars) + ")"));
    } else {
      int forced = r.below(f.arity());
      List<String> args = new ArrayList<>();
      for (int a = 0; a < f.arity(); a++) {
        List<String> values = new ArrayList<>();
        int count = r.between(1, 2);
        for (int k = 0; k < count; k++) values.add(k == 0 && otherKind && a == forced ? kind(r) : value(r));
        drawn.addAll(values);
        args.add(String.join(", ", values));
      }
      b.append(guarded(f, f.name() + "(" + String.join("; ", args) + ")"));
    }
    return new Generated(seed, f, b.toString(), drawn);
  }

  /** {@code try call catch .}, the call's result through {@code p} when the builtin is computed. */
  private static String guarded(Builtin f, String call) {
    return f.exact() ? "try " + call + " catch ." : "try (" + call + " | p) catch .";
  }

  /** A value of the draw's weights: an edge 35, random bits 20, a multiple of 1/8 35, another kind 10. */
  private static String value(SplitMix64 r) {
    int w = r.below(100);
    if (w < 35) return EDGES.get(r.below(EDGES.size()));
    if (w < 55) return bits(r);
    if (w < 90) return eighths(r);
    return kind(r);
  }

  private static String kind(SplitMix64 r) {
    return KINDS.get(r.below(KINDS.size()));
  }

  /** A finite double from a sign, an exponent field in 0..2046 and 52 random bits. */
  private static String bits(SplitMix64 r) {
    long sign = (long) r.below(2) << 63;
    long exponent = (long) r.below(2047) << 52;
    long fraction = r.nextLong() & ((1L << 52) - 1);
    return Double.toString(Double.longBitsToDouble(sign | exponent | fraction));
  }

  /** A multiple of 1/8 in −8..8, in plain decimals. */
  private static String eighths(SplitMix64 r) {
    return BigDecimal.valueOf(r.between(-64, 64)).divide(BigDecimal.valueOf(8)).stripTrailingZeros().toPlainString();
  }
}
