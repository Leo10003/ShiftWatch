import argparse, json
from pathlib import Path

DAYS = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]

def load(p): return json.loads(Path(p).read_text(encoding="utf-8"))

def truth(doc):
    shifts = doc.get("shifts")
    if not isinstance(shifts, list):
        raise ValueError("Truth file has no shifts array.")
    if not shifts:
        raise ValueError(
            "Truth file contains zero confirmed shifts. "
            "Re-export the labelled truth after confirming the rota."
        )

    return {
        int(x["weekday"]): {
            "block": int(x["physicalBlock"]),
            "verticalDecile": x.get("verticalDecile"),
        }
        for x in shifts
        if x.get("physicalBlock") is not None
    }

def automatic(doc):
    return {int(x["weekdayColumn"]): {"block": int(x["physicalBlockIndex"]), "verticalDecile": x.get("verticalDecile")}
            for x in doc.get("viewerMarkers", {}).get("markerDetails", [])
            if not x.get("confirmed") and x.get("physicalBlockIndex") is not None}

def same(e, a):
    if a is None or e["block"] != a["block"]: return False
    ev, av = e.get("verticalDecile"), a.get("verticalDecile")
    return True if ev is None or av is None else abs(int(ev)-int(av)) <= 1

def evaluate(label, auto_path, truth_path):
    a, e = automatic(load(auto_path)), truth(load(truth_path))
    errors = 0
    print(label)
    for d in range(7):
        ex, ac = e.get(d), a.get(d)
        if ex is None and ac is None: status = "PASS/OFF"
        elif ex is None: status, errors = "FALSE", errors + 1
        elif ac is None: status, errors = "MISS", errors + 1
        elif same(ex, ac): status = "PASS"
        else: status, errors = "WRONG", errors + 1
        suffix = "" if status in ("PASS","PASS/OFF") else f" expected={ex} actual={ac}"
        print(f"  {DAYS[d]:<3} {status}{suffix}")
    print()
    return errors, any(v.get("verticalDecile") is None for v in e.values())

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", nargs=3, action="append", required=True, metavar=("LABEL","AUTOMATIC","TRUTH"))
    args = ap.parse_args()
    total = 0
    block_only = False
    for label, auto, truth_file in args.case:
        errors, old = evaluate(label, auto, truth_file)
        total += errors
        block_only = block_only or old
    print(f"TOTAL ERRORS: {total}")
    if block_only:
        print("NOTE: at least one truth file lacks verticalDecile; those rows are block-only until re-exported with 0.9.5+.")
    raise SystemExit(1 if total else 0)

if __name__ == "__main__":
    main()
