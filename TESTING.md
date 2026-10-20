# Validation record · 0.1

Validation date: 2026-10-20 UTC. Synthetic fiction only. No live AI provider or API key was used.

## Automated results

- Final aggregate: testDebugUnitTest, assembleDebug and lintDebug passed together. Lint: 0 errors, 7 warnings (dependency updates and KTX suggestions).
- 37 tests passed, 0 failed, 0 skipped: CoreTest 21, AiTest 7, EditingTest 4, ReaderUiTest 5.
- Core: UTF-8/GB18030 import, EPUB order and inert text, immutable originals, revisions/rollback/recovery, backup hashes and exact member set, path traversal, directory payload/bomb protection, size/depth bounds, export rejecting corrupted originals.
- AI contract: request scope and payload, strict candidate JSON parsing, HTTPS endpoint/address guards. These are offline contract tests, not successful provider requests.
- Editing: unique-source replacement, protected phrase counts, changed-span diff, rejecting a late older draft.
- UI: NATIVE Robolectric API 35 Compose semantics and seven screenshots; bookshelf, reader/editor, actual edit persistence with original preserved, AI workbench cancel without writes, draft retained on reopening and round-trip to secure connection settings, settings/import.
- Environment: JDK 21, Gradle 9.1.0, AGP 9.0.1, SDK 36; Compose BOM 2026.03.01, Robolectric 4.17, Roborazzi 1.76.0. UI viewport Chinese locale, 393×852 dp, xhdpi.

## Investigated regression

The original long scrolling rewrite AlertDialog failed host idle synchronization immediately after opening. It reproduced with API 34 automatic/manual clocks and API 35 isolated NATIVE and LEGACY runs. The original logs are retained locally, not uploaded with source. The production interface was redesigned as a full-screen workbench with a scrollable form and fixed actions. The same cancel/no-write assertions pass; no assertion was removed and no arbitrary sleep was used to mask this failure.

## Not established by these results

These are host-rendered Android UI tests, not a physical phone or emulator session. System file pickers, Android Keystore behavior on hardware, keyboard/IME, process recreation, accessibility/large-font/small-screen behavior, low disk/memory, and the full device acceptance checklist remain unverified. Real provider compatibility, billing, latency and generation quality are unverified. The JSON input guard bounds depth and structure but is not a full strict grammar parser. DNS checks and connection resolution are separate, not a guarantee against DNS rebinding.

Follow ACCEPTANCE.md on the intended Android phone before entrusting unique manuscripts to this prototype. Keep exported backups outside the app.
