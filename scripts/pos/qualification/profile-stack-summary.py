"""Summarize allow-listed kinds of work from bounded external native JVM snapshots."""
from collections import Counter
import json
from pathlib import Path
import re
import sys

stage = Path(sys.argv[1])
categories = {
    "sqlite": ("androidx.sqlite.", "AndroidSelfOwnedRoomDriver", "AndroidOwnedRoomCoroutines"),
    "retained_frames": ("RetainedOriginalReader", "StockFrameStreamDecoderV2"),
    "stock_producer": ("LocalStockRelationalInspection", "StockOriginalTraversal", "StockReadProducerProof"),
    "catalogue": ("LocalCatalogUnitAuthority", "LocalCatalogPublication"),
    "crypto": ("java.security.", "sun.security.", "org.bouncycastle."),
    "journal": ("SelfOwnedNativeHistory", "SelfOwnedHistoryStorage", "NativeHistoryJournal"),
    "payment_producer": ("NormalizedPaymentTraversal", "LocalFinancialEvidenceV3"),
    "history_replay": ("RuntimeHistoryScanner", "RuntimeHistoryColdReplay"),
}
profiles = []
for path in sorted(stage.glob("profile-*.txt")):
    match = re.fullmatch(r"profile-(\d{1,4})-(start|apply)\.txt", path.name)
    if not match:
        continue
    counts = Counter()
    for block in re.split(r'\n(?=")', path.read_text(errors="replace")):
        if "java.lang.Thread.State: RUNNABLE" not in block or "com.ukkera.pos." not in block:
            continue
        counts["runnable_pos_stacks"] += 1
        for category, needles in categories.items():
            if any(needle in block for needle in needles):
                counts[category] += 1
    profiles.append({"sale": int(match[1]), "stage": match[2], "stack_membership_counts": dict(counts)})
print(json.dumps(profiles, indent=2))
