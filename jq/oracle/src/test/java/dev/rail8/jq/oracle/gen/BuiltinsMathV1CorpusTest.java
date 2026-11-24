package dev.rail8.jq.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.oracle.diff.CaseFormat;
import dev.rail8.jq.oracle.diff.DiffCorpus;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The committed {@code diff/gen/builtins-math-v1.cases|.rec}: written by {@code scripts/oracle-record.sh --generate
 * builtins-math-v1 0 408} and frozen, so the Generator must still produce every byte of it.
 */
class BuiltinsMathV1CorpusTest {

  private static final Pattern DROPPED = Pattern.compile("# dropped (\\d+) (rc3|timeout)");

  private static Path diff() {
    try {
      return Path.of(BuiltinsMathV1CorpusTest.class.getResource("/diff").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String committed() throws Exception {
    return Files.readString(diff().resolve("gen/builtins-math-v1.cases"));
  }

  /** The Dropped seeds of a case file's header, with their reasons. */
  private static Map<Long, String> dropped(String cases) {
    Map<Long, String> out = new TreeMap<>();
    for (String line : cases.split("\n")) {
      Matcher m = DROPPED.matcher(line);
      if (m.matches()) out.put(Long.parseLong(m.group(1)), m.group(2));
    }
    return out;
  }

  @Test
  void generatedEqualsCommitted() throws Exception {
    String cases = committed();

    assertThat(ValuesV1CorpusTest.withoutDeviationLines(cases)).isEqualTo(BuiltinsMathV1.file(BuiltinsMathV1.FROM, BuiltinsMathV1.TO, dropped(cases)));
  }

  @Test
  void dropsAndBlocksPartitionRange() throws Exception {
    String cases = committed();
    Map<Long, String> dropped = dropped(cases);
    List<Long> seeds = new ArrayList<>(dropped.keySet());
    for (CaseFormat.Block b : CaseFormat.parse("builtins-math-v1.cases", BuiltinsMathV1.FAMILY, cases.getBytes(UTF_8))) {
      seeds.add(Long.parseLong(b.id().substring(BuiltinsMathV1.FAMILY.length() + 1)));
    }
    seeds.sort(null);

    List<Long> range = new ArrayList<>();
    for (long s = BuiltinsMathV1.FROM; s < BuiltinsMathV1.TO; s++) range.add(s);
    assertThat(seeds).isEqualTo(range);
    assertThat(cases.lines().filter(l -> l.startsWith("# dropped"))).hasSize(dropped.size());
  }

  /** Every call is under {@code try … catch .} and gives a result or a message, so every recording prints with rc 0. */
  @Test
  void atLeastTwoHundredRecordingsAllPrintingWithRcZero() {
    List<DiffCorpus.Entry> entries = DiffCorpus.load(diff()).stream().filter(e -> e.c().id().startsWith(BuiltinsMathV1.FAMILY + "/")).toList();

    assertThat(entries.size()).as("ADR-0020's floor after drops").isGreaterThanOrEqualTo(200);
    for (DiffCorpus.Entry e : entries) {
      assertThat(e.recording()).as(e.c().id()).isNotNull();
      assertThat(e.recording().def()).as(e.c().id()).isEqualTo(e.c().def());
      assertThat(e.recording().outcome().rc()).as(e.c().id()).isZero();
      assertThat(e.recording().outcome().stdout()).as(e.c().id()).isNotEmpty();
      assertThat(e.recording().outcome().stderr()).as(e.c().id()).isEmpty();
    }
  }
}
