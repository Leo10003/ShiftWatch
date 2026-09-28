#!/usr/bin/env python3
"""Read-only, offline comparison of complete schema-15 ShiftWatch diagnostic exports.

Same OCR fingerprint supports a same-layout comparison, NOT proof of the same photo
or of an unchanged training profile. Different fingerprints mean candidate changes
may originate upstream; the tool does not attribute causes automatically.
"""
import argparse
import json
from pathlib import Path
import sys

DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")


class DriftError(ValueError):
    pass


def load(path):
    try:
        raw = Path(path).read_text(encoding="utf-8-sig")
        data, end = json.JSONDecoder().raw_decode(raw.lstrip())
        if raw.lstrip()[end:].strip():
            raise DriftError(f"{path}: trailing text or concatenated JSON")
    except (OSError, UnicodeError, json.JSONDecodeError) as ex:
        raise DriftError(f"{path}: unreadable or invalid JSON ({ex})") from ex
    if not isinstance(data, dict) or type(data.get("schemaVersion")) is not int or data["schemaVersion"] < 15:
        raise DriftError(f"{path}: requires diagnostic schema >= 15")
    viewer = data.get("viewerMarkers")
    if not isinstance(viewer, dict) or viewer.get("savedProfileStatus") != "completed":
        raise DriftError(f"{path}: saved-profile recognition is not completed")
    if data.get("scanStage") != "COMPLETE" or data.get("scanInProgress") is not False:
        raise DriftError(f"{path}: scan did not finish")
    if not isinstance(data.get("sessionId"), str) or not data["sessionId"] or not isinstance(viewer.get("recognitionRunId"), str) or not viewer["recognitionRunId"]:
        raise DriftError(f"{path}: missing session or recognition run ID")
    rows = viewer.get("savedProfileDecisions")
    if not isinstance(rows, list) or len(rows) != 7:
        raise DriftError(f"{path}: expected exactly 7 saved-profile decisions")
    indexed = {}
    for row in rows:
        if not isinstance(row, dict) or type(row.get("weekdayColumn")) is not int or row["weekdayColumn"] not in range(7) or row["weekdayColumn"] in indexed:
            raise DriftError(f"{path}: invalid or duplicate weekday")
        replay = row.get("shadowReplay")
        if not isinstance(replay, dict) or replay.get("baselineParity") is not True or replay.get("originalStatus") != row.get("decision"):
            raise DriftError(f"{path}: baseline parity/status failed on weekday {row['weekdayColumn']}")
        if row.get("decision") not in ("accepted_normal", "accepted_rescue", "rejected_below_rescue_floor", "rejected_insufficient_runner_margin", "rejected_confuser", "rejected_no_candidate", "rejected_no_profile") and not (isinstance(row.get("decision"), str) and (row['decision'].startswith("rejected_") or row['decision'].startswith("accepted_"))):
            raise DriftError(f"{path}: invalid decision status")
        indexed[row["weekdayColumn"]] = row
    return data, indexed


def accepted(row):
    return row["decision"].startswith("accepted_")


def block(row):
    # A rejected top candidate is not a displayed suggestion.
    return row["shadowReplay"].get("originalWinnerBlock") if accepted(row) else None


def compare(first, second, names=("scan1", "scan2")):
    a, x = first
    b, y = second
    av, bv = a["viewerMarkers"], b["viewerMarkers"]
    if a["sessionId"] == b["sessionId"] or av["recognitionRunId"] == bv["recognitionRunId"]:
        raise DriftError("Scan exports reuse a session or recognitionRunId: not independent runs")
    same = a.get("ocrGeometryFingerprint") and a.get("ocrGeometryFingerprint") == b.get("ocrGeometryFingerprint")
    lines = ["# ShiftWatch scan drift comparison", "", "Read-only diagnostic comparison; not a recognition accuracy score or a profile identity test.", "",
             f"Files: `{Path(names[0]).name}` vs `{Path(names[1]).name}`", "",
             f"App versions: {a.get('appVersion', '?')} vs {b.get('appVersion', '?')}", "",
             f"OCR fingerprint: {'MATCH' if same else 'DIFFERENT / UNAVAILABLE'}", ""]
    if same:
        lines += ["Matching OCR geometry supports comparing recognition decisions with the same observed layout, but does not prove identical images, equal training examples, or unchanged runtime conditions.", ""]
    else:
        lines += ["**Caution:** upstream OCR geometry changed or fingerprint is unavailable. Decision differences cannot be attributed to profile/recognition drift alone.", ""]
        ab, bb = a.get("ocrGeometryXBucketFingerprints"), b.get("ocrGeometryXBucketFingerprints")
        if isinstance(ab, list) and isinstance(bb, list) and len(ab) == len(bb) == 7:
            lines += ["Changed x-buckets (0–6): " + (", ".join(str(i) for i in range(7) if ab[i] != bb[i]) or "none") + ".", ""]
    lines += ["| Day | Production 1 | Production 2 | Top 1 → 2 | Replay 1 | Replay 2 | Candidate counts 1 → 2 |",
              "|---|---|---|---:|---|---|---:|"]
    changed, scores, replay_changed, pipeline_changed = [], [], [], []
    for d in range(7):
        old, new = x[d], y[d]
        def state(row):
            return f"{row['decision']}" + (f" (B{block(row)+1})" if block(row) is not None else "")
        def replay(row):
            r = row["shadowReplay"]
            winner = r.get("replayWinnerBlock") if r.get("replayAccepted") else None
            return f"{r.get('replayStatus')}" + (f" (B{winner+1})" if type(winner) is int and winner in range(3) else "")
        if state(old) != state(new): changed.append(DAYS[d])
        if old.get("topScore") != new.get("topScore") or old.get("runnerScore") != new.get("runnerScore"): scores.append(DAYS[d])
        if replay(old) != replay(new): replay_changed.append(DAYS[d])
        oldp, newp = old.get("candidatePipeline") or {}, new.get("candidatePipeline") or {}
        if oldp != newp: pipeline_changed.append(DAYS[d])
        def fmt(value): return f"{value:.3f}" if type(value) in (int, float) else "n/a"
        lines.append(f"| {DAYS[d]} | {state(old)} | {state(new)} | {fmt(old.get('topScore'))} → {fmt(new.get('topScore'))} | "
                     f"{replay(old)} | {replay(new)} | {old.get('candidateLineCount', '?')} → {new.get('candidateLineCount', '?')} |")
    def describe(xs): return ", ".join(xs) if xs else "none"
    lines += ["", f"Production decision changes: {describe(changed)}.",
             f"Top/runner score changes: {describe(scores)}.",
             f"Hypothetical replay changes: {describe(replay_changed)}.",
             f"Candidate pipeline changes: {describe(pipeline_changed)}.", "",
             "Accepted decisions are suggestions only; no confirmed shift labels are inferred. Experimental replay never changes production.",
             "Training-profile changes are not captured by these sanitized diagnostics; do not attribute differences to training without independent evidence."]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("followup", type=Path)
    parser.add_argument("--output", type=Path, help="Optional Markdown output path")
    args = parser.parse_args(argv)
    try:
        report = compare(load(args.baseline), load(args.followup), (args.baseline, args.followup))
        if args.output:
            args.output.write_text(report, encoding="utf-8")
        else:
            print(report, end="")
    except DriftError as exc:
        print(f"COMPARISON FAILED: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
