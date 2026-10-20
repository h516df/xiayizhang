# Readium reading and bounded mature editing (draft)

This is an integration change, not a version bump, APK release, or real-device acceptance.
Baseline: released 0.3 commit `3634dc0195f8e06afa4d8a5fa2e12e42c7b5d305`.
The reused local source index was verified as candidate tree `356e5141e908ec58191c0865ff9b7d453dd4c6d3`.
Remote PR readback revealed that the final release commit additionally updated RELEASE.md
with cloud verification notes. Those notes are preserved unchanged; the candidate tree
is not claimed to be the complete final release documentation tree. No user books, provider keys, signing files, or images belong in this PR.

## Decision and scope

- Use Readium Kotlin Toolkit **3.3.0 EpubNavigatorFragment**, not its alpha Compose successor.
  Readium handles reflow, paging/scroll, font scale, line height, themes, and locators.
- Use stable Compose Rich Editor **1.0.0**, with its history API and equal-length
  replacement fix verified in this exact tag. Initial rc14 integration exposed a real
  undo failure when “再见” was replaced by the same-length “你好”; rc14 was rejected.
  The stable version fixes that classifier. Its published AAR and Compose 1.11.2
  UI dependency require only compileSdk 35 / AGP 8.6, not the SDK 37 used to build
  its upstream sample. Actual app compilation/runtime results are recorded below.
  Use its BasicRichTextEditor inside the existing Material3 shell, excluding the unused
  alpha Material3 wrapper dependency. This is library editing, not a new home-grown editor.
- The model remains plain text. Only a selected range (at most 12,000 UTF-16 code units)
  enters the editor. Do not expose rich styling controls whose formatting would be discarded.
  The library supplies editing, IME integration, undo/redo; the app supplies review/apply.
  A plain-text clipboard adapter strips HTML and never coerces URI/Intent clips, so
  pasted HTML cannot introduce rich objects or trigger remote image fetching.
- No copy of Kelivo/Pagekin UI, no whole-app embedding, no external accounts or key access.

## Data and location contracts

The original source.bin remains byte-for-byte intact. Existing JSON workspaces,
chapter IDs, revisions/epochs, cards, bindings, history and backup code are unchanged.
The reading surface is an ephemeral XHTML projection of ONE working chapter, with
all content escaped; this does **not** restore layout, pictures or typography discarded
by the pre-existing EPUB-to-text importer. Original EPUB bytes remain available in backups.

Readium's 3.3.0 currentSelection returns quote context rather than exact source offsets.
The app calls its public evaluateJavascript API with a fixed application-owned program:
DOM Range lengths from #source yield UTF-16 offsets, verified against the exact source
substring before issuing the existing version-bound SourceAnchor. It never searches for
the first matching quote. This handles repeated text, cross-paragraph selection and emoji.

The selected range stays in the mounted reader under a dialog. Accept makes one guarded
store edit and remaps the saved visible offset through that edit; undo maps it back.
An empty #resume marker is emitted at the stored source offset, allowing Readium to
navigate to the exact source anchor without text matching. This targets the page/position
containing the anchor, not a promise of identical pixels after font or text changes.

Old chapter-index preferences remain in use. Legacy pixel positions are retained unchanged
and migrated once in scroll mode; because the old font/layout metrics were not stored,
this migration is approximate and the UI explicitly says reflow may shift the position.
New positions are stored as source offsets plus Readium locators, never whole-book Bundles.

## Security

No original HTML, CSS, scripts, remote images, links or archive paths are served to Readium.
The publication container exposes exactly chapter.xhtml, rejecting all other URLs.
CSP denies network connections, frames, objects, form actions, images, and base URI changes.
It permits only the exact packaged Readium reflow script and packaged Readium CSS/fonts,
plus inline styles used by the engine. User content is never interpolated into JavaScript.
There is no added JavaScript-to-native file/network/command bridge. Necessary Readium
internal JavaScript is distinct from forbidden book-supplied scripts.

AI still uses the existing provider settings and explicit selection/context preview. It
never uploads a whole book by default. No real provider request or credentials are used
in this work. Fixture-generated candidates are controller tests, not real-AI evidence.

## Validation boundary

Model/storage tests cover long synthetic Chinese books, exact repeated-text offsets,
emoji/whitespace, guarded edits, undo, original-byte retention, backup and recovery.
Controller tests inject explicit selection events at the Readium-to-workspace boundary;
they are NOT evidence that WebView selection or Readium pagination works on Android.
Compose tests exercise the actual packaged rich editor and existing candidate/review UI.
Robolectric does not execute a production Android WebView engine.

Required before merge/release: run the actual MainActivity on an Android WebView device
or existing emulator, including real long-press handles across newlines/emoji, duplicate
quotes, pagination/scroll, every theme/font/line-height combination, viewport restoration,
IME composition and selection, rotate/background/reopen, accept/undo, and legacy migration.
No new emulator/system images are installed as part of this cloud task. Physical-device
and real-provider acceptance remain pending and must not be replaced by fake screenshots.

## Verified cloud results

- Stable Readium 3.3.0 + Rich Editor 1.0.0 compiled with the existing Kotlin 2.3.20,
  AGP 9.0.1 and SDK 36. No metadata-validation bypass, Kotlin upgrade or version bump.
  Release dexing exposed older bundled D8's Kotlin 2.4 metadata warning; the supported
  standalone D8/R8 override is pinned to Google Maven stable 9.1.56 (the documented
  minimum 9.1.29 is not published there). This is a build-tool-only compatibility update,
  not a wholesale Android toolchain upgrade. See the
  [Android compatibility table](https://developer.android.com/build/kotlin-support) and
  [official R8 override instructions](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-android-gradle-plugin).
- 26 focused tests passed: ReadingIntegrationTest 4, ReadiumEntryTest 2,
  ReaderUiTest 11, CleanupUiTest 5, ReferenceImportUiTest 4.
- The editor test actually replaces “再见” with “你好”, invokes the library's undo and
  redo, reviews/applies it to the second duplicate occurrence, then undoes the stored edit.
- Actual MainActivity mounted the Readium Fragment with a synthetic million-character
  Chinese book. This checks Android entry wiring, NOT real WebView layout or JavaScript.
- Source models, backup/recovery, unsafe URL rejection and plain-text clipboard pass.
- After visual review, the editor gained an expandable source excerpt and both manual
  and AI review use readable original/modified panels, not algorithm explanations.
  Three focused UI checks then passed on the final presentation: same-length undo/redo,
  full synthetic Chinese paragraph review, and 8,499 UTF-16 units containing repeated
  lines/indentation/emoji round-tripping through library history into the review model.
  The library's EditableText semantics encode paragraph separators as spaces; the long
  test verifies reconstructed plain text in review, not that internal representation.
- Host-rendered editor/candidate screens were visually checked. They are not device
  reading screenshots, and fixture candidates are not real model output.
- `assembleRelease` and `lintDebug` passed after the dexer update. Lint: 0 errors,
  20 warnings (14 UseKtx, 4 GradleDependency, 1 ApplySharedPref, 1 ObsoleteSdkInt).
  Final build contains no D8 Kotlin-metadata parsing warnings. An earlier attempt's
  daemon disappeared; its cause was not established and it is not counted as success.
- Unsigned, non-debuggable, non-minified release APK: **16,397,841 bytes (15.64 MiB)**;
  released signed 0.3 reference: **8,190,874 bytes (7.81 MiB)**. Approximate growth:
  8,206,967 bytes / 7.83 MiB, 2.00×. Both are release variants, but signature differences
  remain and this is not an identical signing pipeline comparison.
  Compressed ZIP contents: DEX 14,658,855 bytes, Readium assets 335,889, native libraries
  37,392, other 1,265,513 (ZIP headers/alignment account for the remainder). Growth is
  primarily added library/runtime code, not manuscript or screenshot assets. Shrinking
  was deliberately not enabled without device validation just to improve the number.
- APK manifest: cn.privatereader, 0.3/code3, min26/target36, four ABIs. No release
  signing configuration was added. It is a development validation artifact, NOT an
  upgrade package to distribute. The merged library manifest adds normal permissions
  ACCESS_NETWORK_STATE and WAKE_LOCK; this integration does not start audio playback,
  background downloads or acquire wake locks itself. Inspect these again before release.
- The APK and host screenshots remain outside Git; only source/tests/docs/licenses ship
  in this draft PR. No real device, IME or provider result is inferred from them.

Upstream versions reviewed:
[Readium 3.3.0](https://github.com/readium/kotlin-toolkit/tree/3.3.0),
[Rich Editor 1.0.0](https://github.com/MohamedRejeb/Compose-Rich-Editor/tree/v1.0.0).


### First build evidence

`compileDebugKotlin` initially passed with Readium 3.1.2 and Rich Editor rc14 in 57 seconds.
Follow-up version review selected Readium 3.3.0 instead: its Kotlin 2.3.20 / AGP 9.0
generation exactly matches this app, with stable page-turn animation and asset-CORS fixes.
The latest 3.4.0 requires Kotlin 2.4.20; no broad upgrade is needed for 3.3.0.
Final pinned 3.3.0 build results follow.
Readium 3.3.0 additionally requires core-library desugaring; it is enabled with the
upstream 2.1.5 library. The 24-test first integration run passed 23 tests, including
actual MainActivity→Readium Fragment mounting and long-book storage checks, but
exposed the rc14 equal-length undo defect. That result was not accepted as completion.
Stable editor 1.0.0 validation follows; compilation alone is not WebView/IME evidence.

### Device checklist (required, not claimed as run)

1. Open a synthetic million-character Chinese TXT and an EPUB backup from 0.3.
   Check directory, chapter/page progress, font 16/20/32, line spacing, day/paper/night,
   swipe/button paging, continuous scroll and return from the shelf.
2. Long-press the SECOND identical sentence, include an emoji, blank line, indentation
   and part of the next paragraph. Choose 自己改 from WebView's contextual menu.
   Undo/redo typing, review, accept, verify only that occurrence changed, then undo
   the accepted edit and verify original source and reference cards stayed intact.
3. Cancel the editor and AI preview; verify the same source location remains visible.
   Accept a longer replacement above the current viewport, then reopen this chapter:
   verify the source-offset remap targets the correct paragraph, not the first duplicate.
4. Exercise Chinese IME composition, drag selection handles over page/paragraph edges,
   rotate/background/reopen the actual activity. Confirm stored position and unchanged
   files; pending unsaved dialogs are not a crash-recovery draft store.
5. Load inert text containing script/img/iframe/file URLs and HTML entities. Confirm
   they remain literal text; no external WebView requests, no scripts run from the book.
6. With a locally configured provider and explicit user-approved sample request, verify
   the actual provider response/cancel/failure/accept paths. This task has no credentials
   or authorization to manufacture such a real-provider result.
