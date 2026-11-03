#!/usr/bin/env python3
"""Summarize NovelSpeak TTS benchmark JSON files into a readable report."""
import csv
import json
import statistics
import sys
from pathlib import Path


def median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def fmt(value, digits=1):
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def main(out_dir):
    out = Path(out_dir)
    files = [p for p in sorted(out.glob("*.json")) if p.name != "summary.json"]
    if not files:
        print("No benchmark JSON files found in", out)
        return 1

    rows = []
    for path in files:
        try:
            data = json.loads(path.read_text())
        except Exception as exc:  # noqa: BLE001
            print(f"skip {path.name}: {exc}")
            continue
        if data.get("model") == "system" and not data.get("ok"):
            rows.append({
                "model": "system",
                "family": "system",
                "threads": 0,
                "ok": False,
                "error": data.get("error", "unavailable"),
                "load_ms": data.get("load_ms"),
                "gen_ms": None,
                "ttfc_ms": None,
                "audio_ms": None,
                "rtf": None,
                "xrt": None,
                "rss_load_mb": None,
                "rss_gen_mb": None,
                "samples": None,
            })
            continue
        if not data.get("ok"):
            rows.append({
                "model": data.get("model", path.stem),
                "family": data.get("family", "?"),
                "threads": data.get("threads"),
                "ok": False,
                "error": data.get("error", "unknown"),
                "load_ms": data.get("load_ms"),
                "gen_ms": None,
                "ttfc_ms": None,
                "audio_ms": None,
                "rtf": None,
                "xrt": None,
                "rss_load_mb": None,
                "rss_gen_mb": None,
                "samples": None,
            })
            continue
        s = data.get("summary", {})
        m = data.get("memory", {})
        rows.append({
            "model": data.get("model", path.stem),
            "family": data.get("family", "?"),
            "threads": data.get("threads"),
            "ok": True,
            "error": "",
            "load_ms": data.get("load_ms"),
            "gen_ms": s.get("gen_ms_median"),
            "ttfc_ms": s.get("first_chunk_ms_median"),
            "audio_ms": s.get("audio_ms_median"),
            "rtf": s.get("rtf_median"),
            "xrt": s.get("x_realtime_median"),
            "rss_load_mb": m.get("rss_after_load_mb"),
            "rss_gen_mb": m.get("rss_after_gen_mb"),
            "samples": s.get("n"),
        })

    if not rows:
        print("No successful benchmark results in", out)
        return 1

    # CSV
    csv_path = out / "summary.csv"
    with csv_path.open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    # JSON
    (out / "summary.json").write_text(json.dumps(rows, indent=2))

    # Markdown
    header = (
        "| Model | Family | Threads | OK | Cold load | Median synth | TTFC | Audio | RTF | xRT | RSS after load |\n"
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n"
    )
    lines = []
    for r in rows:
        ok = "yes" if r["ok"] else "no"
        threads = r.get("threads")
        threads = "-" if threads in (None, 0) else str(threads)
        if not r["ok"]:
            lines.append(
                f"| {r['model']} | {r['family']} | {threads} | {ok} | {fmt(r['load_ms'],0)} ms | - | - | - | - | - | - |"
            )
            continue
        lines.append(
            "| {model} | {family} | {threads} | {ok} | {load} ms | {gen} ms | {ttfc} ms | {audio} ms | {rtf} | {xrt} | {rss} MB |".format(
                model=r["model"],
                family=r["family"],
                threads=threads,
                ok=ok,
                load=fmt(r["load_ms"], 0),
                gen=fmt(r["gen_ms"], 0),
                ttfc=fmt(r["ttfc_ms"], 0),
                audio=fmt(r["audio_ms"], 0),
                rtf=fmt(r["rtf"], 3),
                xrt=fmt(r["xrt"], 2),
                rss=fmt(r["rss_load_mb"], 0),
            )
        )
    markdown = "# NovelSpeak TTS benchmark\n\n" + header + "\n".join(lines) + "\n"
    (out / "summary.md").write_text(markdown)

    print()
    print(markdown)
    print(f"Wrote {csv_path}, {out/'summary.json'}, {out/'summary.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
