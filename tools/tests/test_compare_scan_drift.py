"""Synthetic tests: no private scans or user training data in source control."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'compare-scan-drift.py'
spec = importlib.util.spec_from_file_location('scan_drift', SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def scan(session='a', fingerprint='same'):
    return {'schemaVersion':15,'appVersion':'20.8.18','sessionId':session,
            'scanStage':'COMPLETE','scanInProgress':False,'ocrGeometryFingerprint':fingerprint,
            'ocrGeometryXBucketFingerprints':['same']*7,
            'viewerMarkers':{'savedProfileStatus':'completed','recognitionRunId':session+'-run',
                             'savedProfileDecisions':[{'weekdayColumn':d,'decision':'rejected_below_rescue_floor',
                                                       'topScore':0.4,'runnerScore':0.3,'candidateLineCount':2,
                                                       'candidatePipeline':{'finalCandidates':2},
                                                       'shadowReplay':{'baselineParity':True,'originalStatus':'rejected_below_rescue_floor',
                                                                       'originalWinnerBlock':2,'replayStatus':'rejected_below_rescue_floor',
                                                                       'replayWinnerBlock':2,'replayAccepted':False}}
                                                      for d in range(7)]}}


class TestScanDrift(unittest.TestCase):
    def setUp(self):
        self.a, self.b = scan(), scan('b')
    def test_unchanged_runs(self):
        report=mod.compare((self.a,{d:r for d,r in enumerate(self.a['viewerMarkers']['savedProfileDecisions'])}),
                           (self.b,{d:r for d,r in enumerate(self.b['viewerMarkers']['savedProfileDecisions'])}))
        self.assertIn('Production decision changes: none.',report)
        self.assertIn('OCR fingerprint: MATCH',report)
    def test_accepted_and_rejected_candidate_distinguished(self):
        self.b['viewerMarkers']['savedProfileDecisions'][2]['decision']='accepted_normal'
        self.b['viewerMarkers']['savedProfileDecisions'][2]['shadowReplay']['originalStatus']='accepted_normal'
        with tempfile.TemporaryDirectory() as d:
            p,q=Path(d)/'a.json',Path(d)/'b.json'
            p.write_text(json.dumps(self.a)); q.write_text(json.dumps(self.b))
            report=mod.compare(mod.load(p),mod.load(q))
        self.assertIn('Production decision changes: Wed.',report)
        self.assertIn('rejected_below_rescue_floor | accepted_normal (B3)',report)
    def test_upstream_ocr_mismatch_not_attributed_to_training(self):
        self.b['ocrGeometryFingerprint']='different'
        self.b['ocrGeometryXBucketFingerprints'][3]='changed'
        with tempfile.TemporaryDirectory() as d:
            p,q=Path(d)/'a.json',Path(d)/'b.json'
            p.write_text(json.dumps(self.a)); q.write_text(json.dumps(self.b))
            result=mod.compare(mod.load(p),mod.load(q))
        self.assertIn('DIFFERENT / UNAVAILABLE',result)
        self.assertIn('Changed x-buckets (0–6): 3.',result)
    def test_duplicate_run_rejected(self):
        self.b['viewerMarkers']['recognitionRunId']=self.a['viewerMarkers']['recognitionRunId']
        with self.assertRaisesRegex(mod.DriftError,'not independent'):
            mod.compare((self.a,{}),(self.b,{}))
    def test_incomplete_scan_rejected(self):
        self.a['scanStage']='VERIFY'
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.json';p.write_text(json.dumps(self.a))
            with self.assertRaisesRegex(mod.DriftError,'did not finish'): mod.load(p)
    def test_incomplete_recognition_rejected(self):
        self.a['viewerMarkers']['savedProfileStatus']='not_attempted'
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.json';p.write_text(json.dumps(self.a))
            with self.assertRaisesRegex(mod.DriftError,'not completed'): mod.load(p)
    def test_baseline_parity_rejected(self):
        self.a['viewerMarkers']['savedProfileDecisions'][3]['shadowReplay']['baselineParity']=False
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.json';p.write_text(json.dumps(self.a))
            with self.assertRaisesRegex(mod.DriftError,'parity'): mod.load(p)
    def test_trailing_bytes_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.json';p.write_text(json.dumps(self.a)+'}garbage')
            with self.assertRaisesRegex(mod.DriftError,'trailing'): mod.load(p)
    def test_seven_unique_weekdays_required(self):
        self.a['viewerMarkers']['savedProfileDecisions'][1]['weekdayColumn']=0
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.json';p.write_text(json.dumps(self.a))
            with self.assertRaisesRegex(mod.DriftError,'duplicate'): mod.load(p)


if __name__=='__main__': unittest.main()
