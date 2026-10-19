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
        "/var/log/gemfire-nightly-stats": str(root / "logs"),
        "/etc/logrotate.d": str(root / "logrotate.d"),
        "/etc/systemd/system": str(units),
        "/etc/os-release": str(root / "os-release"),
        "/run/systemd/system": str(root / "systemd"),
    }.items():
        script = script.replace(before, after)
    installer.write_text(script)
    stopper = source / "installation/stop.sh"
    stopper.write_text(stopper.read_text().replace("/run/systemd/system", str(root / "systemd")))
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
    executable(tools / "logrotate", ":")
    executable(tools / "runuser", 'shift 3\nexec "$@"')
    executable(tools / "systemd-analyze", '''[[ "$1" = calendar && "$2" = -- ]]
printf '%s\n' "$3" >> "$FIXTURE/calendar-validations"
[[ "$3" = '*-*-* 22:00:00 America/Chicago' || "$3" = '*-*-* 22:00:00 UTC' ]]''')
    real_install = shutil.which("install")
    executable(tools / "install", '''args=()
while (($#)); do
  case "$1" in -o|-g) shift 2 ;; *) args+=("$1"); shift ;; esac
done
exec "$REAL_INSTALL" "${args[@]}"''')
    executable(tools / "systemctl", '''printf 'systemctl|%s\n' "$*" >> "$FIXTURE/trace"
case "$1" in
  is-active)
    if [[ "${*: -1}" = *.service ]]; then [[ -f "$FIXTURE/service-active" ]];
    else [[ -f "$FIXTURE/active" ]]; fi ;;
  enable) touch "$FIXTURE/enabled" ;;
  disable) rm -f "$FIXTURE/enabled" "$FIXTURE/active" ;;
  stop) rm -f "$FIXTURE/active" "$FIXTURE/service-active" ;;
  restart)
    [[ "${TEST_TIMER_START_FAIL:-false}" != true ]] || exit 1
    touch "$FIXTURE/active" ;;
  list-timers) echo 'NEXT LEFT LAST PASSED UNIT ACTIVATES' ;;
esac''')
    for manager in ("apt", "apt-get", "yum", "dnf"):
        executable(tools / manager, 'echo package-manager-called >> "$FIXTURE/trace"\nexit 99')
    environment = os.environ.copy()
    environment.update(PATH=str(tools) + os.pathsep + environment["PATH"],
                       FIXTURE=str(root), REAL_INSTALL=real_install)
    environment.pop("GEMFIRE_HOME", None)
    environment.pop("JAVA_HOME", None)
    base = ["bash", str(installer), "--gemfire-home", str(gemfire), "--java-home", str(java_home)]

    def run(*options, success=True, version="17", timer_start_failure=False):
        env = dict(environment, TEST_JAVA_VERSION=version,
                   TEST_TIMER_START_FAIL=str(timer_start_failure).lower())
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
    staged = run()
    config = root / "config/collector.properties"
    env_file = root / "config/collector.env"
    assert stat.S_IMODE(config.stat().st_mode) == 0o640
    assert stat.S_IMODE(env_file.stat().st_mode) == 0o640
    assert not (root / "active").exists()
    assert (root / "enabled").exists()
    assert "Timer: STOPPED" in staged.stdout
    assert "Automatic collection has not started" in staged.stdout
    assert (root / "app/lib/02-standalone-jmx.jar").read_bytes() == b"example-jar"
    assert (root / "app/bin/stop.sh").read_text() == stopper.read_text()
    assert stat.S_IMODE((root / "app/bin/stop.sh").stat().st_mode) == 0o755
    log = root / "logs/collector.log"
    assert log.exists()
    assert stat.S_IMODE(log.stat().st_mode) == 0o640
    assert stat.S_IMODE(log.parent.stat().st_mode) == 0o750
    assert stat.S_IMODE((root / "app/bin/collect-stats-logged.sh").stat().st_mode) == 0o755
    assert (root / "logrotate.d/gemfire-nightly-stats").read_text() == (PROJECT / "installation/gemfire-nightly-stats.logrotate").read_text()
    log.write_text("previous collection log\n")
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
    assert log.read_text() == "previous collection log\n"
    text = env_file.read_text()
    assert "JMX_PASSWORD" not in text
    assert "AWS_SECRET_ACCESS_KEY" not in text
    assert 'JAVA_OPTS="-Xmx512m"' in text
    assert sum(line.startswith("GEMFIRE_HOME=") for line in text.splitlines()) == 1
    assert "OnUnitInactiveSec=30s" in (units / "gemfire-nightly-stats.timer").read_text()
    trace_before = (root / "trace").read_text()
    run("--start", "--no-start", success=False)
    assert (root / "trace").read_text() == trace_before
    # Explicit staging must also override the resume behavior of an active installation.
    deferred = run("--no-start")
    assert not (root / "active").exists()
    assert "Timer: STOPPED" in deferred.stdout
    assert config.read_text() == configured_text
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
    # A supplied config starts a previously stopped timer without a separate --start.
    started = run("--config", str(custom), "--interval-seconds", "30")
    assert config.read_text() == custom.read_text()
    assert (root / "active").exists(), "--config must start scheduling automatically"
    assert "Timer: active and enabled for boot" in started.stdout
    assert "NEXT LEFT LAST PASSED" in started.stdout
    assert "ExecMainExitTimestamp" in started.stdout
    assert str(root / "app/bin/stop.sh") in started.stdout
    run("--config", str(custom), "--no-start")
    assert not (root / "active").exists(), "--no-start must override --config"
    failed_start = run("--config", str(custom), success=False, timer_start_failure=True)
    assert "Could not start gemfire-nightly-stats.timer" in failed_start.stderr
    assert "Timer: active" not in failed_start.stdout
    run("--start")
    run("--interval-seconds", "30")
    assert (root / "active").exists(), "An active timer must resume after updating"
    assert "package-manager-called" not in (root / "trace").read_text()

    # Invalid calendar values fail before stopping services or replacing installed files.
    schedule = units / "gemfire-nightly-stats.timer.d/zz-collector-schedule.conf"
    old_schedule = schedule.read_text()
    old_config = config.read_text()
    trace_before = (root / "trace").read_text()
    custom.write_text("ENV=calendar-test\nCOLLECTION_CALENDAR=not a schedule\n")
    invalid_calendar = run("--config", str(custom), success=False)
    assert "Invalid COLLECTION_CALENDAR" in invalid_calendar.stderr
    assert (root / "trace").read_text() == trace_before
    assert schedule.read_text() == old_schedule
    assert config.read_text() == old_config

    # The last property wins; comments and quotes are handled without interpreting shell syntax.
    custom.write_text('ENV=calendar-test\n# COLLECTION_CALENDAR=ignored\n'
                      'COLLECTION_CALENDAR=invalid earlier entry\n'
                      '  COLLECTION_CALENDAR = "*-*-* 22:00:00 America/Chicago"  \n')
    calendar_install = run("--config", str(custom), "--interval-seconds", "30")
    assert "calendar schedule: *-*-* 22:00:00 America/Chicago" in calendar_install.stdout
    assert "takes precedence over --interval-seconds" in calendar_install.stdout
    assert "First collection in about 10 seconds" not in calendar_install.stdout
    calendar_lines = schedule.read_text().splitlines()
    assert "OnCalendar=*-*-* 22:00:00 America/Chicago" in calendar_lines
    assert "OnActiveSec=" in calendar_lines and "OnUnitInactiveSec=" in calendar_lines
    assert "OnActiveSec=10s" not in calendar_lines and "OnUnitInactiveSec=30s" not in calendar_lines
    assert stat.S_IMODE(schedule.stat().st_mode) == 0o644
    assert (root / "calendar-validations").read_text().splitlines()[-1] == "*-*-* 22:00:00 America/Chicago"

    # Reinstallation reads the existing installed properties when --config is omitted.
    retained = run()
    assert "calendar schedule: *-*-* 22:00:00 America/Chicago" in retained.stdout
    assert config.read_text() == custom.read_text()
    # The managed schedule is later than the earlier manual override and resets its triggers.
    manual_override = schedule.parent / "override.conf"
    manual_override.write_text("[Timer]\nOnCalendar=*-*-* 01:00:00\n")
    assert sorted(schedule.parent.glob("*.conf"))[-1] == schedule
    custom.write_text("ENV=calendar-test\nCOLLECTION_CALENDAR: *-*-* 22:00:00 UTC\n")
    run("--config", str(custom), "--no-start")
    assert "OnCalendar=*-*-* 22:00:00 UTC" in schedule.read_text()
    assert manual_override.read_text() == "[Timer]\nOnCalendar=*-*-* 01:00:00\n"
    assert not (root / "active").exists()

    # Clearing the calendar restores interval collection, including its startup trigger.
    custom.write_text('ENV=explicit-replacement\nCOLLECTION_CALENDAR=""\n')
    run("--config", str(custom), "--interval-seconds", "30")
    interval_lines = schedule.read_text().splitlines()
    assert "OnCalendar=" in interval_lines
    assert not any(line.startswith("OnCalendar=") and line != "OnCalendar=" for line in interval_lines)
    assert "OnActiveSec=10s" in interval_lines and "OnUnitInactiveSec=30s" in interval_lines

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
    assert "collect-stats-logged.sh collect --config /etc/gemfire-nightly-stats/collector.properties" in service["Service"]["ExecStart"]
    assert service["Service"]["LogsDirectory"] == "gemfire-nightly-stats"
    assert service["Service"]["LogsDirectoryMode"] == "0750"
    assert service["Service"]["StandardOutput"] == "journal"
    assert service["Service"]["StandardError"] == "journal"
    assert service["Service"]["KillMode"] == "control-group"

    # Exercise the installed wrapper: capture stdout/stderr and preserve all collector exit codes.
    executable(root / "app/bin/collect-stats.sh", '''printf '%s\\n' "$*" >> "$FIXTURE/collector-calls"
echo 'report_file=/state/unique-report.json'
echo 'upload result or error from collector stderr' >&2
exit "${TEST_COLLECTOR_EXIT:-0}"''')
    wrapper = root / "app/bin/collect-stats-logged.sh"
    for exit_status in (0, 2, 3, 4):
        wrapper_env = dict(environment, LOGS_DIRECTORY=str(log.parent), TEST_COLLECTOR_EXIT=str(exit_status))
        captured = subprocess.run(["bash", str(wrapper), "collect", "--config", str(config)],
                                  env=wrapper_env, text=True, capture_output=True)
        assert captured.returncode == exit_status, captured.stdout + captured.stderr
        assert captured.stdout in log.read_text(), "Journal output must also be in the physical log"
        assert "Z Collection started." in captured.stdout
        assert f"Z Collection finished; exit_status={exit_status}." in captured.stdout
        assert "report_file=/state/unique-report.json" in captured.stdout
        assert "upload result or error from collector stderr" in captured.stdout
    assert (root / "collector-calls").read_text().splitlines() == [f"collect --config {config}"] * 4
    assert log.read_text().startswith("previous collection log\n")
    # A log setup failure must fail the service without starting an unlogged collection.
    broken_log = subprocess.run(["bash", str(wrapper), "collect", "--config", str(config)],
                               env=dict(environment, LOGS_DIRECTORY=str(root / "missing-logs")),
                               text=True, capture_output=True)
    assert broken_log.returncode != 0
    assert len((root / "collector-calls").read_text().splitlines()) == 4
    saved_logs = log.read_text()
    run("--interval-seconds", "30")
    assert log.read_text() == saved_logs, "Reinstallation must retain existing logs"

    # Stopping disables scheduling before stopping a collection, preserves data and is repeatable.
    (root / "service-active").touch()
    trace_before = len((root / "trace").read_text().splitlines())
    stopped = subprocess.run(["bash", str(root / "app/bin/stop.sh")], env=environment,
                             text=True, capture_output=True)
    assert stopped.returncode == 0, stopped.stdout + stopped.stderr
    assert not (root / "active").exists()
    assert not (root / "service-active").exists()
    assert not (root / "enabled").exists()
    assert report.read_text() == "retain this report"
    assert log.read_text() == saved_logs
    assert config.read_text() == custom.read_text()
    assert "Resume collection: sudo systemctl enable --now" in stopped.stdout
    stop_trace = (root / "trace").read_text().splitlines()[trace_before:]
    assert stop_trace[:2] == ["systemctl|disable --now gemfire-nightly-stats.timer",
                              "systemctl|stop gemfire-nightly-stats.service"]
    repeated_stop = subprocess.run(["bash", str(stopper)], env=environment,
                                  text=True, capture_output=True)
    assert repeated_stop.returncode == 0, repeated_stop.stdout + repeated_stop.stderr

print("Installer checks passed: preflight, updates, scheduling, journal/file logging, exit codes, log preservation and shutdown.")
