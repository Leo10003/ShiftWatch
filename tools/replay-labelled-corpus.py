#!/usr/bin/env python3
import argparse, json
from pathlib import Path

DAYS = ("Mon","Tue","Wed","Thu","Fri","Sat","Sun")

def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))

def _i(v):
    try:
        return None if v is None else int(v)
    except (TypeError, ValueError):
        return None

def truth(doc):
    shifts = doc.get("shifts")
    if not isinstance(shifts, list):
        raise ValueError("Truth file has no shifts array.")
    out = {}
    for s in shifts:
        if not isinstance(s, dict):
            continue
        day, block = _i(s.get("weekday")), _i(s.get("physicalBlock"))
        if day in range(7) and block is not None:
            out[day] = {
                "block": block,
                "rowYPermille": _i(s.get("rowYPermille")),
                "verticalDecile": _i(s.get("verticalDecile")),
            }
    if not out:
        raise ValueError("Truth file contains zero confirmed shifts. Re-export the labelled truth after confirming the rota.")
    return out

def automatic(doc):
    markers = ((doc.get("viewerMarkers") or {}).get("markerDetails") or [])
    out = {}
    for m in markers:
        if not isinstance(m, dict):
            continue
        day, block = _i(m.get("weekdayColumn")), _i(m.get("physicalBlockIndex"))
        if day in range(7) and block is not None:
            out.setdefault(day, []).append({
                "block": block,
                "rowYPermille": _i(m.get("rowYPermille")),
                "verticalDecile": _i(m.get("verticalDecile")),
                "origin": m.get("origin"),
            })
    return out

def row_status(actual, expected, tolerance):
    erow, arow = expected.get("rowYPermille"), actual.get("rowYPermille")
    if erow is not None:
        if arow is None:
            return False, "row geometry missing"
        delta = abs(arow - erow)
        return delta <= tolerance, f"row delta={delta}‰"
    ed, ad = expected.get("verticalDecile"), actual.get("verticalDecile")
    if ed is not None and ad is not None:
        return ad == ed, "legacy vertical-decile check"
    return True, "legacy block-only truth"

def evaluate(label, auto_path, truth_path, tolerance):
    a, e = automatic(load(auto_path)), truth(load(truth_path))
    errors, legacy = 0, False
    print(label)
    for day in range(7):
        expected, actuals = e.get(day), a.get(day, [])
        if expected is None:
            if actuals:
                errors += 1
                print(f"  {DAYS[day]} FALSE expected=OFF actual={actuals}")
            else:
                print(f"  {DAYS[day]} PASS/OFF")
            continue
        if not actuals:
            errors += 1
            print(f"  {DAYS[day]} MISS expected={expected} actual=None")
            continue
        if len(actuals) != 1:
            errors += 1
            print(f"  {DAYS[day]} AMBIGUOUS expected={expected} actual={actuals}")
            continue
        actual = actuals[0]
        if actual["block"] != expected["block"]:
            errors += 1
            print(f"  {DAYS[day]} FALSE expected={expected} actual={actual}")
            continue
        ok, note = row_status(actual, expected, tolerance)
        legacy = legacy or expected.get("rowYPermille") is None
        if ok:
            suffix = "" if note.startswith("row delta=") else f" [{note}]"
            print(f"  {DAYS[day]} PASS{suffix}")
        else:
            errors += 1
            print(f"  {DAYS[day]} WRONG-ROW expected={expected} actual={actual} ({note})")
    print()
    return errors, legacy

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--case", action="append", nargs=3, metavar=("LABEL","AUTOMATIC_JSON","TRUTH_JSON"), required=True)
    p.add_argument("--row-tolerance", type=int, default=18)
    args = p.parse_args()
    total, legacy = 0, False
    for label, auto_path, truth_path in args.case:
        errors, old = evaluate(label, auto_path, truth_path, args.row_tolerance)
        total += errors
        legacy = legacy or old
    print(f"TOTAL ERRORS: {total}")
    if legacy:
        print("ROW VALIDATION WARNING: at least one truth file is legacy schema-v2; re-export labelled truth with the current app for precise row identity.")
    raise SystemExit(1 if total else 0)

if __name__ == "__main__":
    main()
