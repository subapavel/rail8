"""Tests for ci/check: which areas it runs, when it stops before an area, and the git, clock and JVM options it runs
them with.

Run: python3 -m unittest ci.test_check  (from the repository root; ci/check guard runs it)

Each case runs ci/check of this repository with stubs of java, node, npm and python3 first on the PATH, in a temporary
HOME whose git configuration names a user, so no case builds anything or starts Docker.
"""
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CHECK = REPO / "ci" / "check"
TIMEOUT = 120


class CheckTest(unittest.TestCase):

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.bin = self.dir / "bin"
        self.bin.mkdir()
        (self.dir / ".gitconfig").write_text("[user]\n\tname = Someone\n\temail = someone@example.invalid\n")
        self.java("25")
        self.node("v22.1.0")
        self.stub("npm", "echo 10.0.0")
        # the guard area's python3: writes the clock's zone and git's user name it sees, and passes
        self.stub("python3", f'echo "TZ=$TZ user=$(git config --get user.name) _JAVA_OPTIONS=$_JAVA_OPTIONS '
                             f'JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >> "{self.dir}/seen"')

    def stub(self, name: str, body: str):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(path.stat().st_mode | stat.S_IEXEC)

    def java(self, specification: str):
        """A java that reports the specification version given, and fails at anything else than reporting it."""
        self.stub("java", textwrap.dedent(f"""\
            case "$*" in
              *-XshowSettings*) echo "    java.specification.version = {specification}" >&2 ;;
              -version) echo 'java version "{specification}"' >&2 ;;
              *) exit 1 ;;
            esac"""))

    def node(self, version: str):
        self.stub("node", f"echo {version}")

    def run_check(self, *args: str, command: str = str(CHECK), cwd: Path = REPO, **extra: str) -> subprocess.CompletedProcess:
        env = {k: v for k, v in os.environ.items() if not k.startswith(("JAVA_", "_JAVA_", "GIT_", "TZ", "MAVEN", "GITHUB_"))}
        env.update(extra)
        env["PATH"] = f"{self.bin}:{env.get('PATH', '/usr/bin:/bin')}"
        env["HOME"] = str(self.dir)
        return subprocess.run([command, *args], cwd=cwd, env=env, capture_output=True, text=True, timeout=TIMEOUT)

    def test_an_unknown_area_is_a_usage_error(self):
        result = self.run_check("nope")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("ci/check <area>", result.stderr)

    def test_another_node_major_stops_the_area_before_it_starts(self):
        self.node("v20.11.0")
        result = self.run_check("ui")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertIn("ci/check ui: NOT RUN: Node.js 22 is needed (.nvmrc), node on the PATH is 20", result.stderr)

    def test_another_java_stops_the_area_before_it_starts(self):
        self.java("21")
        result = self.run_check("jq")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertIn("ci/check jq: NOT RUN: Java 25 is needed (.java-version), java on the PATH is 21", result.stderr)

    def test_a_java_that_cannot_start_stops_the_area_before_it_starts(self):
        # what a shell answers for a command it cannot find: exit 127 and nothing on stdout
        self.stub("java", "exit 127")
        result = self.run_check("jq")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertIn("ci/check jq: NOT RUN: Java 25 is needed (.java-version), java on the PATH is missing", result.stderr)

    def test_several_areas_from_a_subfolder_by_a_relative_path(self):
        self.java("21")
        self.node("v20.11.0")
        result = self.run_check("jq", "ui", command="../ci/check", cwd=REPO / "ui")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertIn("ci/check jq: NOT RUN", result.stderr)
        self.assertIn("ci/check ui: NOT RUN", result.stderr)

    def test_several_areas_each_run_and_a_failure_outweighs_an_area_not_run(self):
        # mapping fails at its first script test, the java stub refusing to run it; ui cannot run on node 20
        self.node("v20.11.0")
        result = self.run_check("mapping", "ui")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("ci/check mapping: FAILED", result.stderr)
        self.assertIn("ci/check ui: NOT RUN", result.stderr)

    def test_several_areas_not_run_end_as_not_run(self):
        self.java("21")
        self.node("v20.11.0")
        result = self.run_check("jq", "ui")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertIn("ci/check jq: NOT RUN", result.stderr)
        self.assertIn("ci/check ui: NOT RUN", result.stderr)

    def test_an_area_runs_in_utc_with_git_and_java_options_as_on_a_runner(self):
        result = self.run_check("guard", _JAVA_OPTIONS="-Dmachine=1", JAVA_TOOL_OPTIONS="-Dtool=1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("ci/check guard: passed", result.stdout)
        seen = (self.dir / "seen").read_text().splitlines()
        self.assertEqual(["TZ=UTC user= _JAVA_OPTIONS= JAVA_TOOL_OPTIONS=-Dtool=1 -Dmachine=1"] * 3, seen)


if __name__ == "__main__":
    unittest.main()
