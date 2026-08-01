# Adaptive Training Engine — Approval Checklist

**Status:** Audit & design doc is complete (committed at `38a5d5a`/`7fd4345`). This is a summary of exactly what's awaiting your approval before implementation begins.

---

## §19 Approval Items

These five items must be approved before Phase 0 implementation plan is created:

### 1. Overall Sequencing
**Proposal:** Phase 0/1 (contracts + pain-wiring) before any new strength/conditioning recommendation logic, and AI strictly deferred to Phase 5.

**Current state:** Pain-stop wiring is already shipped (commits `6a9de1c`/`46676ea`/`078f8b3`). Conditioning setup screen now blocks until athlete acknowledges a recent pain-stop.

**Decision needed:** ✅ Approve the phase ordering shown in §14's roadmap table.

---

### 2. Coach-System Removal (SETTLED 2026-08-01)
**Status:** ✅ **ALREADY DECIDED AND SHIPPED**
- App-code removal complete (commits `9c37e5a`, `c59c780`, `ea43ee8`)
- Database schema half pending: you'll run the `DROP TABLE` script yourself against Supabase when ready
- Exact scope in §13; irreversible schema action only requires your separate confirmation at that time

**Remaining housekeeping:** Rotate Concept2 client secret + update Netlify env var; scrub two WHOOP token literals in `.superpowers/sdd/2026-07-31-conditioning-evidence-based-upgrade/task-10-{brief,report}.md`.

---

### 3. Pain-Stop/Local-Fatigue Wiring Approach (§16)
**Proposal:** Hold-until-acknowledged UX for pain-stop signals. When `mechanicalCompletion: 'pain_stop'` exists for a modality, today's prescription is held with explicit reason-code, requiring athlete acknowledgement to proceed.

**Current state:** ✅ **ALREADY SHIPPED** — Conditioning setup shows a banner and blocks "Start" until acknowledged (`painHoldFor()` in engine, UI in both apps).

**Decision needed:** Confirm this hold-until-acknowledged behavior is the right first safety signal, since it's the first thing in this project that changes what an athlete experiences.

---

### 4. Tech-Debt Items to Fold Into Phase 6
**Open tech-debt (independent of adaptive project):**
- Backup-restore platform divergence (web merge-default, mobile always full-replace)
- Duplicate-abandoned-workout guard (real UX gap, documented in §12)
- Concept2 client secret rotation + Netlify env var update
- WHOOP token literals in `.superpowers/sdd/` scrub
- One pre-existing `whoop-contract.mjs` failure (non-blocking, already flagged)

**Decision needed:** Which of these should be bundled into Phase 6 now vs. deferred? (All are independent of the adaptive project; bundling them keeps the scope clear.)

---

### 5. Phase 3 Modality-Specific Conditioning Thresholds
**Proposal:** Use the already-in-repo evidence bundle (`docs/research/conditioning-evidence-bundle/modality_progression_regression_trees.json`) to drive modality-aware thresholds in Phase 3.

**Current state:** Evidence is sourced and in-repo; Phase 3 will consume it directly.

**Decision needed:** Ship Phase 3 thresholds from the existing evidence bundle as-is, or request a fresh evidence review first?

---

## Next Steps (Waiting for Your Approval)

Once you confirm the above five items:

1. Create a `writing-plans`-style task-by-task implementation plan for **Phase 0 only** (contracts + wrapping, zero behavior change)
2. Present Phase 0 plan for review
3. Begin Phase 0 implementation once that plan is approved
4. Phase 1 (pain-wiring) is already partially shipped; remaining pieces can be implemented once Phase 0 is merged

---

## Quick Facts

- **Codebase health:** All CI green (308/308 engine tests + 71 mobile + 3 web + 15 guided-flow + react-smoke + deploy-smoke + pentest + contracts)
- **Golden suite:** Untouched at 33/33 cases (additive work only)
- **Current branch:** `main` at commit `7fd4345`
- **Work location:** `/workspace/the-hybrid-engine1`
