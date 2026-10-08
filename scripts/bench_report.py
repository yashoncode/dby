"""Summarise DBY vs Tevel timings from a logcat dump into a Markdown table.

Usage:
  python scripts/bench_report.py docs/benchmarks/raw/<scenario>.txt [gfx-dby.txt gfx-tevel.txt]
  python scripts/bench_report.py --self-test
"""
import re
import statistics
import sys

LINE = re.compile(r"DBYBENCH:? app=(\w+) event=(\w+) ms=(\d+)")
JANK = re.compile(r"Janky frames: (\d+) \(([\d.]+)%\)")
EVENTS = ["connect", "first_page", "next_page", "sql"]


def timings(text):
    found = {}
    for app, event, ms in LINE.findall(text):
        found.setdefault((app, event), []).append(int(ms))
    return found


def jank(text):
    match = JANK.search(text)
    return float(match.group(2)) if match else None


def cell(values):
    return f"{statistics.median(values):.0f} ({len(values)})" if values else "-"


def table(found):
    rows = ["| Event | DBY median ms (runs) | Tevel median ms (runs) | DBY faster? |", "|---|---|---|---|"]
    for event in EVENTS:
        dby = found.get(("dby", event), [])
        tevel = found.get(("tevel", event), [])
        if not (dby or tevel):
            continue
        if dby and tevel:
            verdict = "yes" if statistics.median(dby) < statistics.median(tevel) else "**no**"
        else:
            verdict = "-"
        rows.append(f"| {event} | {cell(dby)} | {cell(tevel)} | {verdict} |")
    return "\n".join(rows)


def self_test():
    sample = (
        "10-08 I DBYBENCH: app=dby event=connect ms=300 rows=0\n"
        "10-08 I DBYBENCH: app=dby event=connect ms=500 rows=0\n"
        "10-08 I Capacitor/Console: File: x - Line 1 - Msg: DBYBENCH app=tevel event=connect ms=900\n"
    )
    found = timings(sample)
    assert found[("dby", "connect")] == [300, 500]
    assert found[("tevel", "connect")] == [900]
    assert "| connect | 400 (2) | 900 (1) | yes |" in table(found)
    assert "first_page" not in table(found)
    assert jank("Janky frames: 12 (3.45%)") == 3.45
    print("self-test ok")


def main(argv):
    if argv[1:] == ["--self-test"]:
        self_test()
        return
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        print(table(timings(f.read())))
    for path in argv[2:]:
        with open(path, encoding="utf-8", errors="replace") as f:
            print(f"\n{path}: janky frames {jank(f.read())}%")


if __name__ == "__main__":
    main(sys.argv)
