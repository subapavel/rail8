package dev.rail8.jq.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.oracle.diff.CaseFormat;
import dev.rail8.jq.oracle.diff.DiffCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The builtins-math-v1 Generator over its whole seed range. */
class BuiltinsMathV1Test {

  /** Every test draws the range itself: PIT credits a static initializer to no test, so its mutants would live. */
  private final List<BuiltinsMathV1.Generated> generated = all();

  private static List<BuiltinsMathV1.Generated> all() {
    List<BuiltinsMathV1.Generated> out = new ArrayList<>();
    for (long seed = BuiltinsMathV1.FROM; seed < BuiltinsMathV1.TO; seed++) out.add(BuiltinsMathV1.generate(seed));
    return out;
  }

  /** The exact math builtins of jq 1.8.2: IEEE 754 or C fixes every bit of their results. */
  private static final Set<String> EXACT =
      Set.of(
          "ceil", "copysign", "drem", "fabs", "fdim", "floor", "fma", "fmax", "fmin", "fmod", "frexp", "ldexp", "logb", "modf",
          "nearbyint", "nextafter", "nexttoward", "remainder", "rint", "round", "scalb", "scalbln", "significand", "sqrt",
          "trunc");

  /** The computed math builtins the generator calls: every one but the Bessel and gamma families. */
  private static final Set<String> COMPUTED =
      Set.of(
          "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh", "cbrt", "cos", "cosh", "erf", "erfc", "exp", "exp10",
          "exp2", "expm1", "hypot", "log", "log10", "log1p", "log2", "pow", "sin", "sinh", "tan", "tanh");

  /** The Bessel and gamma builtins, which come after the generator. */
  private static final Set<String> LATER = Set.of("gamma", "lgamma", "lgamma_r", "tgamma", "j0", "j1", "jn", "y0", "y1", "yn");

  /** The words of a filter besides its builtin: the printer's exact functions, the syntax and the literals. */
  private static final Set<String> WORDS =
      Set.of(
          "def", "p", "if", "then", "elif", "else", "end", "isnan", "isinfinite", "isnormal", "copysign", "logb", "significand",
          "rint", "try", "catch", "as", "infinite", "nan", "null", "true", "false");

  private static final Pattern STRING = Pattern.compile("\"[^\"]*\"");

  /** A name: not a variable, and not the exponent letter of a number literal. */
  private static final Pattern NAME = Pattern.compile("(?<![$0-9.])[a-z_][a-z0-9_]*");

  private static Set<String> names(String filter) {
    Set<String> out = new HashSet<>();
    Matcher m = NAME.matcher(STRING.matcher(filter).replaceAll("\"\""));
    while (m.find()) out.add(m.group());
    return out;
  }

  /** The filter after the printer's definition. */
  private static String body(BuiltinsMathV1.Generated g) {
    return g.builtin().exact() ? g.filter() : g.filter().substring(BuiltinsMathV1.PRINTER.length());
  }

  @Test
  void theSeedRangeIsEightSeedsPerBuiltin() {
    assertThat(BuiltinsMathV1.FROM).isZero();
    assertThat(BuiltinsMathV1.TO).isEqualTo(408);
    assertThat(BuiltinsMathV1.FAMILY).isEqualTo("gen/builtins-math-v1");
    assertThat(BuiltinsMathV1.BUILTINS).hasSize(BuiltinsMathV1.COUNT);
    Map<String, Integer> seeds = new TreeMap<>();
    for (BuiltinsMathV1.Generated g : generated) seeds.merge(g.builtin().name(), 1, Integer::sum);
    assertThat(seeds).hasSize(51).allSatisfy((name, n) -> assertThat(n).as(name).isEqualTo(8));
  }

  @Test
  void theBuiltinsAreTheExactAndComputedOnesWithTheirArities() {
    Set<String> exact = new HashSet<>();
    Set<String> computed = new HashSet<>();
    for (BuiltinsMathV1.Builtin b : BuiltinsMathV1.BUILTINS) {
      (b.exact() ? exact : computed).add(b.name());
      int arity =
          switch (b.name()) {
            case "fma" -> 3;
            case "copysign", "drem", "fdim", "fmax", "fmin", "fmod", "ldexp", "nextafter", "nexttoward", "remainder", "scalb", "scalbln", "atan2", "hypot", "pow" -> 2;
            default -> 0;
          };
      assertThat(b.arity()).as(b.name()).isEqualTo(arity);
    }
    assertThat(exact).isEqualTo(EXACT);
    assertThat(computed).isEqualTo(COMPUTED);
  }

  @Test
  void sameSeedSameBytes() {
    for (long seed = BuiltinsMathV1.TO - 1; seed >= BuiltinsMathV1.FROM; seed--) {
      assertThat(BuiltinsMathV1.generate(seed).block()).as("seed %d", seed).isEqualTo(generated.get((int) seed).block());
    }
  }

  @Test
  void eachBlockIsOneCaseOfTheFamily() {
    for (BuiltinsMathV1.Generated g : generated) {
      String block = g.block();
      DiffCase c = DiffCase.of("builtins-math-v1.cases", CaseFormat.parse("builtins-math-v1.cases", BuiltinsMathV1.FAMILY, block.getBytes(UTF_8)).getFirst());
      assertThat(c.id()).isEqualTo("gen/builtins-math-v1/" + g.seed());
      assertThat(c.args()).containsExactly("-c", g.filter());
      assertThat(c.stdin()).isEqualTo("null\n".getBytes(UTF_8));
      assertThat(c.deviation()).isNull();
    }
  }

  @Test
  void aFilterCallsOnlyItsBuiltinAndThePrintersExactFunctions() {
    for (BuiltinsMathV1.Generated g : generated) {
      Set<String> names = names(g.filter());
      assertThat(names).as("seed %d: %s", g.seed(), g.filter()).contains(g.builtin().name()).doesNotContainAnyElementsOf(LATER);
      names.remove(g.builtin().name());
      assertThat(names).as("seed %d: %s", g.seed(), g.filter()).isSubsetOf(WORDS);
    }
  }

  @Test
  void anExactResultIsRawAndAComputedOneGoesThroughThePrinter() {
    for (BuiltinsMathV1.Generated g : generated) {
      String f = g.builtin().name();
      String body = body(g);
      assertThat(body).as("seed %d", g.seed()).doesNotContain("def ");
      if (g.builtin().exact()) {
        assertThat(g.filter()).as("seed %d", g.seed()).doesNotStartWith("def p:").doesNotContain("| p");
        assertThat(body).as("seed %d", g.seed()).containsOnlyOnce("try " + f).endsWith(" catch .").doesNotContain("try (");
      } else {
        assertThat(g.filter()).as("seed %d", g.seed()).startsWith(BuiltinsMathV1.PRINTER);
        assertThat(body).as("seed %d", g.seed()).containsOnlyOnce("try (" + f).endsWith(" | p) catch .");
      }
    }
  }

  @Test
  void thePrinterIsMadeOfExactFunctions() {
    Set<String> names = names(BuiltinsMathV1.PRINTER);
    names.retainAll(new HashSet<>(List.of("copysign", "logb", "significand", "rint")));
    assertThat(names).isSubsetOf(EXACT).hasSize(4);
    assertThat(BuiltinsMathV1.PRINTER)
        .contains("[logb, (significand * 4294967296 | rint)]")
        .contains("[\"zero\", copysign(1; .)]", "[\"subnormal\", copysign(1; .)]", "[\"infinite\", copysign(1; .)]")
        .contains("if isnan then \"nan\"");
  }

  @Test
  void halfOfEachBuiltinsSeedsCallItOnAnotherKind() {
    Map<String, Integer> other = new TreeMap<>();
    for (BuiltinsMathV1.Generated g : generated) {
      String values = STRING.matcher(body(g)).replaceAll("\"\"");
      boolean kind = values.contains("\"\"") || values.contains("[1]") || values.contains("[]") || values.contains("{\"\":1}");
      kind = kind || names(values).stream().anyMatch(n -> n.equals("null") || n.equals("true") || n.equals("false"));
      if ((g.seed() / BuiltinsMathV1.COUNT / 2) % 2 == 1) assertThat(kind).as("seed %d: %s", g.seed(), g.filter()).isTrue();
      if (kind) other.merge(g.builtin().name(), 1, Integer::sum);
    }
    assertThat(other).hasSize(51).allSatisfy((name, n) -> assertThat(n).as(name).isGreaterThanOrEqualTo(4));
  }

  @Test
  void aBuiltinWithArgumentsHasBothForms() {
    for (BuiltinsMathV1.Generated g : generated) {
      if (g.builtin().arity() == 0) {
        assertThat(body(g)).as("seed %d", g.seed()).doesNotContain(" as [");
        continue;
      }
      boolean tuples = (g.seed() / BuiltinsMathV1.COUNT) % 2 == 0;
      String vars = g.builtin().arity() == 2 ? "$a; $b" : "$a; $b; $c";
      if (tuples) assertThat(body(g)).as("seed %d", g.seed()).contains(" as [").contains(g.builtin().name() + "(" + vars + ")");
      else assertThat(body(g)).as("seed %d", g.seed()).startsWith("try ").doesNotContain(" as [");
    }
  }

  @Test
  void theDrawsReachEveryEdgeEveryKindAndRandomBits() {
    String all = String.join("\n", generated.stream().map(BuiltinsMathV1.Generated::filter).toList());
    for (String edge : BuiltinsMathV1.EDGES) assertThat(all).as(edge).contains(edge);
    for (String kind : BuiltinsMathV1.KINDS) assertThat(all).as(kind).contains(kind);
    assertThat(Pattern.compile("[0-9]E-?[0-9]").matcher(all).results().count()).as("doubles from random bits").isGreaterThan(200);
  }
}
