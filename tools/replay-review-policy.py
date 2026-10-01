import argparse
import json
from collections import Counter

def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)

def truth(doc):
    shifts = doc.get("shifts")
    if isinstance(shifts, list):
        return {
            int(x["weekday"]): {
                "block": int(x["physicalBlock"]),
                "verticalDecile": x.get("verticalDecile"),
            }
            for x in shifts
            if x.get("physicalBlock") is not None
        }

    marker_details = doc.get("viewerMarkers", {}).get("markerDetails", [])
    confirmed = {
        int(x["weekdayColumn"]): {
            "block": int(x["physicalBlockIndex"]),
            "verticalDecile": x.get("verticalDecile"),
        }
        for x in marker_details
        if x.get("confirmed") is True
        and x.get("weekdayColumn") is not None
        and x.get("physicalBlockIndex") is not None
    }
    if confirmed:
        return confirmed

    candidates = doc.get("candidates")
    if isinstance(candidates, list):
        selected = {
            int(x["columnIndex"]): {
                "block": int(x["physicalBlockIndex"]),
                "verticalDecile": x.get("verticalDecile"),
            }
            for x in candidates
            if x.get("selected") is True
            and x.get("identityUserConfirmed") is True
            and x.get("columnIndex") is not None
            and x.get("physicalBlockIndex") is not None
        }
        if selected:
            return selected

    raise ValueError("Unsupported ground-truth JSON.")

def anchors(auto):
    result = []
    for d in auto["viewerMarkers"]["savedProfileDecisions"]:
        if d.get("decision") not in ("accepted_normal", "accepted_near_floor"):
            continue
        top = (d.get("topCandidates") or [None])[0]
        if top and top.get("physicalBlockIndex") is not None:
            result.append((d["weekdayColumn"], top["physicalBlockIndex"], top["verticalDecile"]))
    return result

def review_predictions(auto):
    a = anchors(auto)
    counts = Counter(block for _, block, _ in a)
    if not counts:
        return {}
    high = max(counts.values())
    winners = [b for b,n in counts.items() if n == high]
    if len(winners) != 1 or high < 2:
        return {}
    anchor = winners[0]
    deciles = [d for _,b,d in a if b == anchor]
    mn, mx = min(deciles), max(deciles)
    accepted_cols = {c for c,_,_ in a}
    out = {}

    for d in auto["viewerMarkers"]["savedProfileDecisions"]:
        col = d["weekdayColumn"]
        if col in accepted_cols:
            continue
        tc = d.get("topCandidates") or []
        if not tc:
            continue
        lead = tc[0]
        req = lead.get("requiredSeparation")

        if lead.get("physicalBlockIndex") == anchor and req is not None:
            pos = lead["positiveScore"]
            raw = lead["rawSeparation"]
            pen = lead["confuserPenalty"]
            vd = lead["verticalDecile"]
            inside = mn <= vd <= mx
            ordinary = pos >= .54 and pen <= .020 and raw > -.030 and raw < req and req - raw <= .065
            adjacent = (not inside and mn - 1 <= vd <= mx + 1 and
                        pos >= .58 and pen <= .015 and raw > -.030 and raw < req and
                        req - raw <= .070)
            if (ordinary and inside) or adjacent:
                out[col] = anchor
                continue

        if len(tc) >= 2:
            runner = tc[1]
            rreq = runner.get("requiredSeparation")
            if (lead.get("physicalBlockIndex") != anchor and
                runner.get("physicalBlockIndex") == anchor and
                lead["adjustedScore"] - runner["adjustedScore"] <= .005 and
                mn <= runner["verticalDecile"] <= mx and
                runner["positiveScore"] >= .56 and
                runner["confuserPenalty"] <= .005 and
                runner["rawSeparation"] > -.040 and
                rreq is not None and runner["rawSeparation"] < rreq and
                rreq - runner["rawSeparation"] <= .075):
                out[col] = anchor
    return out

def current(auto):
    return {int(x["weekdayColumn"]): int(x["physicalBlockIndex"])
            for x in auto["viewerMarkers"].get("markerDetails", [])
            if not x.get("confirmed")}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--automatic", required=True)
    ap.add_argument("--truth", required=True)
    args = ap.parse_args()

    auto = load(args.automatic)
    expected = truth(load(args.truth))
    combined = current(auto)
    combined.update(review_predictions(auto))

    false = {d:b for d,b in combined.items() if expected.get(d) != b}
    missed = {d:b for d,b in expected.items() if combined.get(d) != b}

    print("expected:", dict(sorted(expected.items())))
    print("result:  ", dict(sorted(combined.items())))
    print("false:   ", false)
    print("missed:  ", missed)
    raise SystemExit(1 if false or missed else 0)

if __name__ == "__main__":
    main()
