#!/usr/bin/env python3
"""Exercise the Ubuntu installer in a disposable filesystem with mocked host commands."""
import configparser
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile


PROJECT = Path(__file__).resolve().parents[2]


def executable(path, body):
    path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body + "\n")
    path.chmod(0o755)


with tempfile.TemporaryDirectory(prefix="collector-install-check-") as temporary:
    root = Path(temporary)
    source = root / "source"
    shutil.copytree(PROJECT / "installation", source / "installation")
    (source / "lib").mkdir()
    (source / "bin").mkdir()
    (source / "lib/02-standalone-jmx.jar").write_bytes(b"example-jar")
    shutil.copy(PROJECT / "scripts/collect-stats.sh", source / "bin/collect-stats.sh")
    tools = root / "tools"
    tools.mkdir()
    units = root / "units"
    units.mkdir()
    (root / "systemd").mkdir()
    (root / "os-release").write_text("ID=ubuntu\n")
    installer = source / "installation/install.sh"
    script = installer.read_text()
    for before, after in {
        "/opt/gemfire-nightly-stats": str(root / "app"),
        "/etc/gemfire-nightly-stats": str(root / "config"),
        "/var/lib/gemfire-nightly-stats": str(root / "state"),
        "/etc/systemd/system": str(units),
        "/etc/os-release": str(root / "os-release"),
        "/run/systemd/system": str(root / "systemd"),
    }.items():
        script = script.replace(before, after)
    installer.write_text(script)
    gemfire = root / "gemfire"
    (gemfire / "lib").mkdir(parents=True)
    dependencies = gemfire / "lib/gemfire-dependencies.jar"
    dependencies.touch()
    java_home = root / "java"
    (java_home / "bin").mkdir(parents=True)
    executable(java_home / "bin/java", 'echo "    java.specification.version = ${TEST_JAVA_VERSION:-17}" >&2')
    (tools / "java").symlink_to(java_home / "bin/java")
    executable(tools / "uname", "echo Linux")
    executable(tools / "id", "echo 0")
    executable(tools / "readlink", 'python3 -c \'import os,sys; print(os.path.realpath(sys.argv[-1]))\' "$@"')
    executable(tools / "getent", '[[ -f "$FIXTURE/account-$1" ]]')
    executable(tools / "groupadd", 'touch "$FIXTURE/account-group"')
    executable(tools / "useradd", 'touch "$FIXTURE/account-passwd"')
    executable(tools / "chown", ":")
    executable(tools / "runuser", 'shift 3\nexec "$@"')
    real_install = shutil.which("install")
    executable(tools / "install", '''args=()
while (($#)); do
  case "$1" in -o|-g) shift 2 ;; *) args+=("$1"); shift ;; esac
done
exec "$REAL_INSTALL" "${args[@]}"''')
    executable(tools / "systemctl", '''printf 'systemctl|%s\n' "$*" >> "$FIXTURE/trace"
case "$1" in
  is-active) [[ -f "$FIXTURE/active" ]] ;;
  stop) rm -f "$FIXTURE/active" ;;
  restart) touch "$FIXTURE/active" ;;
esac''')
    for manager in ("apt", "apt-get", "yum", "dnf"):
        executable(tools / manager, 'echo package-manager-called >> "$FIXTURE/trace"\nexit 99')
    environment = os.environ.copy()
    environment.update(PATH=str(tools) + os.pathsep + environment["PATH"],
                       FIXTURE=str(root), REAL_INSTALL=real_install)
    environment.pop("GEMFIRE_HOME", None)
    environment.pop("JAVA_HOME", None)
    base = ["bash", str(installer), "--gemfire-home", str(gemfire), "--java-home", str(java_home)]

    def run(*options, success=True, version="17"):
        env = dict(environment, TEST_JAVA_VERSION=version)
        result = subprocess.run(base + list(options), env=env, text=True, capture_output=True)
        assert (result.returncode == 0) == success, result.stdout + result.stderr
        return result

    # Missing/old runtimes and invalid inputs fail before any account, file or unit changes.
    run(success=False, version="11")
    assert not (root / "account-passwd").exists()
    assert not (root / "app").exists()
    run("--interval-seconds", "0", success=False)
    run("--java-home", str(root / "missing-java"), success=False)
    dependencies.unlink()
    run(success=False)
    assert not (root / "config").exists()
    dependencies.touch()

    # First install creates ready-to-edit configuration, enables boot scheduling, and stays stopped.
    run()
    config = root / "config/collector.properties"
    env_file = root / "config/collector.env"
    assert stat.S_IMODE(config.stat().st_mode) == 0o640
    assert stat.S_IMODE(env_file.stat().st_mode) == 0o640
    assert not (root / "active").exists()
    assert (root / "app/lib/02-standalone-jmx.jar").read_bytes() == b"example-jar"
    assert "OnUnitInactiveSec=60s" in (units / "gemfire-nightly-stats.timer").read_text()
    assert "restart" not in (root / "trace").read_text()
    # No --java-home is needed when PATH selects an existing Java runtime.
    detected = subprocess.run(base[:4], env=environment, text=True, capture_output=True)
    assert detected.returncode == 0, detected.stdout + detected.stderr
    configured_text = "ENV=keep-this-config\nJMX_USERNAME=reader\nJMX_PASSWORD=preserve-this-test-value\nS3_ACCESS_KEY=example-key\nS3_SECRET_KEY=example-secret\n"
    config.write_text(configured_text)
    with env_file.open("a") as handle:
        handle.write('JAVA_OPTS="-Xmx512m"\n')
    report = root / "state/reports/saved.json"
    report.write_text("retain this report")

    # Updates preserve user configuration, reports, credentials and JVM options.
    run("--interval-seconds", "30", "--start")
    assert (root / "active").exists()
    assert config.read_text() == configured_text
    assert report.read_text() == "retain this report"
    text = env_file.read_text()
    assert "JMX_PASSWORD" not in text
    assert "AWS_SECRET_ACCESS_KEY" not in text
    assert 'JAVA_OPTS="-Xmx512m"' in text
    assert sum(line.startswith("GEMFIRE_HOME=") for line in text.splitlines()) == 1
    assert "OnUnitInactiveSec=30s" in (units / "gemfire-nightly-stats.timer").read_text()
    # An older env credential must be moved explicitly, never silently lost during an upgrade.
    runtime_text = env_file.read_text()
    env_file.write_text(runtime_text + "JMX_PASSWORD=legacy-test-value\n")
    trace_before = (root / "trace").read_text()
    rejected = run(success=False)
    assert "Move collector credentials" in rejected.stderr
    assert (root / "trace").read_text() == trace_before
    assert config.read_text() == configured_text
    assert "JMX_PASSWORD=legacy-test-value" in env_file.read_text()
    env_file.write_text(runtime_text)
    custom = root / "configured.properties"
    custom.write_text("ENV=explicit-replacement\n")
    run("--config", str(custom), "--interval-seconds", "30")
    assert config.read_text() == custom.read_text()
    assert (root / "active").exists(), "An active timer must resume after updating"
    assert "package-manager-called" not in (root / "trace").read_text()

    # The repository checkout layout is supported as well as the unpacked distribution layout.
    (source / "build/libs").mkdir(parents=True)
    shutil.move(source / "lib/02-standalone-jmx.jar", source / "build/libs/02-standalone-jmx.jar")
    (source / "scripts").mkdir()
    shutil.move(source / "bin/collect-stats.sh", source / "scripts/collect-stats.sh")
    run("--interval-seconds", "30")
    assert report.exists()
    service = configparser.ConfigParser()
    service.read(PROJECT / "installation/gemfire-nightly-stats.service")
    assert service["Service"]["Type"] == "oneshot"
    assert service["Service"]["User"] == "gemfire-stats"
    assert "collect --config /etc/gemfire-nightly-stats/collector.properties" in service["Service"]["ExecStart"]

print("Installer checks passed: preflight, no package installation, both layouts, permissions, updates and timer activation.")
