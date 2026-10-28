#!/usr/bin/env python3
"""Summarizes what DeviceTest measured on each phone, from the results run.sh downloaded,
then compares the phones in one table.

    tools/device-test/summarize.py build/device-test/<run> [more runs...]
"""
import json
import pathlib
import re
import sys


def bench(path):
    """llama-bench's JSON as {(test, threads): tokens per second}."""
    rows = {}
    for r in json.loads(path.read_text() or "[]"):
        test = "pp" if r["n_prompt"] else "tg"
        rows[(test, r["n_threads"])] = r["avg_ts"]
    return rows


def field(lines, prefix):
    return next((line[len(prefix):].strip() for line in lines if line.startswith(prefix)), "")


def phone(d):
    """One phone's results as a dict, printing the details on the way."""
    lines = (d / "device.txt").read_text().splitlines()
    p = {"name": re.sub(r" \(.*", "", lines[0]), "soc": field(lines, "SoC:"), "variant": field(lines, "The app loads:")}
    memory = field(lines, "Memory:")
    p["ram"] = memory.split(",")[0]
    p["model"] = memory.split("testing ")[-1].replace("Qwen 3.5 ", "")
    threads = re.findall(r"\d+", field(lines, "Threads the app uses:"))
    p["gen_t"], p["batch_t"] = (int(threads[0]), int(threads[1])) if len(threads) == 2 else (0, 0)

    print(f"\n## {lines[0]}")
    for line in lines[1:9]:
        if line and not line.startswith("CPU variants"):
            print(f"   {line}")

    # Each variant ran once (variant-NAME.json) or twice, there and back (variant-NAME-1/2.json).
    variants = {}
    for v in sorted(d.glob("variant-*.json")):
        name = re.sub(r"-\d$", "", v.stem.removeprefix("variant-"))
        for key, ts in bench(v).items():
            variants.setdefault(name, {}).setdefault(key, []).append(ts)
    for name, rows in variants.items():
        cells = "  ".join(f"{test} {sum(ts) / len(ts):6.1f} ({t})" for (test, t), ts in sorted(rows.items()))
        print(f"   {name:18} {cells}")

    # One sweep (threads.json), or rounds in different orders to even out throttling, averaged.
    rounds = sorted(d.glob("threads*.json"))
    if rounds:
        samples = {}
        for r in rounds:
            for key, ts in bench(r).items():
                samples.setdefault(key, []).append(ts)
        rows = {key: sum(v) / len(v) for key, v in samples.items()}
        counts = sorted({t for _, t in rows})
        print(f"   threads " + "".join(f"{t:>7}" for t in counts) + f"   ({len(rounds)} round(s))")
        for test in ("tg", "pp"):
            print(f"   {test:7} " + "".join(f"{rows.get((test, t), 0):7.1f}" for t in counts))
            best = max(counts, key=lambda t: rows.get((test, t), 0))
            p[f"{test}_best"] = (rows.get((test, best), 0), best)
            p[f"{test}_app"] = rows.get((test, p["gen_t" if test == "tg" else "batch_t"]), 0)

    engine = d / "engine.txt"
    if engine.exists():
        text = engine.read_text()
        for line in text.splitlines():
            if line.startswith(("loaded", "total:", "memory:")) or "FAILED" in line:
                print(f"   engine: {line}")
        load = re.search(r"^loaded .* in (\d+) ms", text, re.M)
        if load:
            p["load_ms"] = int(load.group(1))
        peak = re.search(r"^memory: .* now, (\d+) MiB at peak", text, re.M)
        if peak:
            p["peak"] = int(peak.group(1))
        wait = re.search(r"total: (\d+) prompt tokens processed, ([\d.]+) s", text)
        if wait:
            p["wait_s"] = float(wait.group(2))
        p["checks"] = "FAILED" if "FAILED" in text else "ok"
    else:
        p["checks"] = "missing"
    app = d / "app.txt"
    if app.exists():
        for line in app.read_text().splitlines()[1:4]:
            print(f"   app: {line}")
    return p


def main(roots):
    phones = [phone(f.parent) for root in roots for f in sorted(pathlib.Path(root).rglob("device.txt"))]
    print("\n| Phone | SoC | RAM | Model | Variant | Threads (gen/prompt) | Generation t/s: app (best @t) "
          "| Prompt t/s: app (best @t) | Load | Peak MiB | Checks |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for p in phones:
        tg_best, tg_t = p.get("tg_best", (0, 0))
        pp_best, pp_t = p.get("pp_best", (0, 0))
        print(f"| {p['name']} | {p['soc']} | {p['ram']} | {p['model']} | {p['variant'].removeprefix('android_')} "
              f"| {p['gen_t']}/{p['batch_t']} | {p.get('tg_app', 0):.1f} ({tg_best:.1f} @{tg_t}) "
              f"| {p.get('pp_app', 0):.0f} ({pp_best:.0f} @{pp_t}) | {p.get('load_ms', 0) / 1000:.1f} s "
              f"| {p.get('peak', 0)} | {p['checks']} |")


if __name__ == "__main__":
    main(sys.argv[1:] or ["build/device-test"])
