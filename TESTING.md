# Validation record · 0.2 writing-workspace slice

2026-10-20 UTC. All writing fixtures are synthetic. No real AI provider, key, billable request or user manuscript was used.

## Reference import increment (stage 2b)

- Focused run: 22 new tests passed (ReferenceImport 12, ReferenceBindings 6, ReferenceImportUi 4), with zero failures/errors. Native screenshots of preview and explicit context picker were inspected locally.
- Independent incremental review identified loose restore-field coercion and an unbounded imported-source title. Both are fixed and statically rechecked; an additional malformed-backup regression passed in the aggregate.
- One stable aggregate testDebugUnitTest + assembleDebug + lintDebug succeeded: 76 tests, zero failures/errors/skips (the original 53 plus parser 12, bindings 7, import UI 4). Debug APK built locally; lint has zero errors and nine dependency-version/KTX suggestion warnings.
- The updated native reader toolbar and green selected preset/intensity chips were also visually inspected. No physical-device or real-provider acceptance is claimed. The 53-test record below is retained as the earlier stage-2a checkpoint.

## Stage 2a results

- Production compilation passed. Focused new model tests: 11 passed. Focused Compose interaction tests: 10 passed.
- One stable aggregate run of testDebugUnitTest, assembleDebug and lintDebug succeeded: 53 tests, zero failures/errors/skips (Core 21, AI 7, legacy Editing 4, StoryModel 11, ReaderUi 10).
- Lint: zero errors, six warnings (dependency versions and KTX suggestions). Debug APK built locally only; not included in source publication.
- After the aggregate, the screenshot harness alone was changed to advance a Compose frame and synchronize before capture, avoiding an image one frame behind the click. Its ten UI tests were rerun separately and all passed; production code was unchanged. Screenshots stay local.

## What the new checks establish

- Exact repeated-phrase offsets, Unicode-boundary protection, protected-phrase checks, stale revision rejection and safe one-step undo.
- Atomic storage reload before adoption; automatic recovery and explicit rollback use a new epoch so old candidate/source anchors cannot revive.
- Stable entity IDs, same-name characters, scene associations, relationships and source anchors survive backup/restore. Baseline version-1 JSON without the new fields still loads deterministically, retaining legacy facts without guessed extraction.
- Request snapshots default to selection/instruction only. Explicit current-scene cards and relations include IDs. Unselected character notes, future-scene material and legacy facts remain absent; no relationship traversal. Duplicate/oversized scene-reference lists are rejected.
- Actual Compose selection → own edit → save → reading → undo, cancellation without writes, unconfigured AI without pretend generation, synthetic candidate review/adoption, failure with retained instruction and retry, stale candidate rejection, reference/outline creation and editing, and reading scroll preservation after cancelling.
- Independent read-only incremental security review identified and rechecked fixes for five issues: ambiguous same-name payloads, whole-text saved-state bloat, recovered anchor reuse, repeated scene-reference cost, and editing during reference-save. No remaining explicit blocker was reported within that review's scope.

## Limits

These are native host-rendered Robolectric API 35/Compose checks, not physical phone or emulator acceptance. Selection handles/IME on hardware, Android Keystore, system pickers, process death, large fonts/small screens, low storage/memory and production provider compatibility remain unverified. AI candidates are synthetic injected test results, not a demonstration of model quality. Provider JSON mode, billing, latency, continuity and output quality are unverified.

Multi-block partial adoption, persisted candidate sessions, structured editable presets, typed continuation/summary, scene splitting and PNG/CHARX card import are not included. Basic character/worldbook JSON import is covered by the separate stage-2b checks above. Current diff is a changed envelope, not a multi-hunk algorithm.

Environment: JDK 21, Gradle 9.1.0, AGP 9.0.1, SDK 36, Compose BOM 2026.03.01, Robolectric 4.17 and Roborazzi 1.76.0. UI fixture viewport: Chinese locale, 393×852 dp, xhdpi. The JSON storage guard bounds depth/structure but is not a complete strict grammar; the provider response parser is stricter. Existing DNS checking and connection resolution remain separate and are not a full rebinding guarantee.

## Earlier baseline

The 0.1 reader baseline had 37 tests and APK/lint checks. Its whole-chapter editing/AI-form UI has been replaced, so those old workflow screenshots are not evidence of this increment. Import, immutable originals, safe backups, history, provider parsing and address-policy regression tests remain covered in the current 53-test aggregate.

Follow ACCEPTANCE.md on the intended phone before entrusting unique manuscripts to the prototype. Keep backups outside the app.
