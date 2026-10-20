# 下一章 · Android 0.1

A local-first personal novel and text reader/editor. This is a private prototype, not a store-published or stable release. All sample fiction is original synthetic text.

## First usable slice

- Import UTF-8 / GB18030 TXT, DRM-free EPUB, plain HTML, or pasted/shared text.
- Local bookshelf, chapter navigation, adjustable text size, reading-position persistence.
- Explicit current-chapter editing with 650 ms debounced autosave and flush on app stop. Ordered tickets prevent a late older draft from overwriting a newer save.
- Exact imported bytes retained separately from editable working text.
- Up to 50 recent revisions within a 40 MB history budget. Rollback retains the current working version.
- Checksummed `.prbook` export with original + working text + bounded revisions. Restore creates a separate new book rather than overwriting an original.
- Recovery from a damaged working file uses newest valid history or hash-verified original, preserving the damaged file and showing a recovery notice.
- Default style → per-book overrides → per-request style, plus facts, custom action buttons, and protected exact phrases.
- HTTPS OpenAI-compatible chat-completions adapter: frozen submitted request, locked controls while generating, explicit selected context disclosure, 2–3 candidates, before/after and changed-span preview, manual acceptance only. Unique-source replacement and protected-phrase occurrence enforcement happen in app code, not just in the prompt.
- API key encrypted with Android Keystore AES-GCM, excluded from OS backup and app exports. No bundled key, default provider, telemetry, cloud sync, public sharing, or automatic book rewriting.

## What is intentionally limited

- No real provider has been configured or called. Provider/network/credential end-to-end behavior is unverified. JSON-mode chat-completions compatibility is required; provider-specific APIs may need another adapter. Each generated request can incur the provider's charges.
- AI input is capped at 12,000 combined UTF-16 characters across passage, style, facts, instruction. Candidate length is at most 12,000. Model output is untrusted; facts/continuity cannot be guaranteed.
- The first UI supports browser-shared text, not fetching a URL's article body. A shared link alone must be replaced with copied accessible text. No login/DRM bypass.
- Input files ≤20 MB, expanded ZIP ≤40 MB, ≤2,000 entries; book ≤5,000 chapter chunks. Chapters are split into at most 12,000 UTF-16 characters to keep editing bounded. EPUB images, styling, footnotes/navigation semantics, and complex layouts are flattened to text. EPUB files containing an encryption manifest are conservatively rejected, including some otherwise DRM-free books with obfuscated fonts.
- Backups must fit the same limits on restore. Very large incompressible books may exceed the export budget and produce a clear error rather than an unusable archive.
- Local data is private within the Android app sandbox, but book content is not separately encrypted at rest. Uninstalling the app removes it; explicitly export backups first.
- Abrupt OS/process loss within the 650 ms autosave window can lose the most recent keystrokes. Normal app stop flushes edits. No claim of zero-loss protection.
- Host native-render screenshots are not physical-phone acceptance. Accessibility, low-memory behavior, keyboard/IME, file pickers, and provider connection require device validation.

## Build and test

JDK 21; Android SDK 36; Gradle 9.1; Android Gradle Plugin 9.0.1. Dependencies are official AndroidX/Kotlin components. Run:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1

`CoreTest` covers import/storage/backup and malicious archives; `AiTest` covers payload, parsing, endpoint and address policies without external requests; `EditingTest` checks immutable selected-source replacement and protected phrases; `ReaderUiTest` exercises native Compose UI using Robolectric/Roborazzi and writes screenshots to `app/build/outputs/reader-ui`.

The repository/publication status is tracked separately from this local build. Do not commit API keys, signing keys, user manuscripts, or private data.
