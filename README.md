# 下一章 · Android 0.3

A local-first personal novel and text reader/editor. The first dedicated-signed installable release is 0.3. It is not store-published; physical-device and real-provider acceptance remain unverified. All sample fiction is original synthetic text.

> **License**: MIT, see [LICENSE](LICENSE).
> **Status**: buildable and usable, but not fully accepted on physical devices yet. Known gaps are tracked in [Issues](https://github.com/h516df/xiayizhang/issues).
> Build with your own signing key; the in-app update path expects the upstream signature and will reject a different one.
> Third-party components and their notices are listed in [THIRD_PARTY.md](THIRD_PARTY.md).

## Selection-first writing and local references

- Import UTF-8 / GB18030 TXT, DRM-free EPUB, plain HTML, or pasted/shared text.
- Local bookshelf, chapter navigation, adjustable text size, reading-position persistence.
- Select text while reading, then choose own editing or AI rewriting. Explicit save/adopt, cancel without writing, and version-checked one-step undo; the reader stays mounted behind the editor.
- Exact imported bytes retained separately from editable working text.
- Up to 50 recent revisions within a 40 MB history budget. Rollback retains the current working version.
- Checksummed `.prbook` export with original + working text + bounded revisions. Restore creates a separate new book rather than overwriting an original.
- Recovery from a damaged working file uses newest valid history or hash-verified original, preserving the damaged file and showing a recovery notice.
- Three real entry points: text, outline, and reference material. Manually create/edit characters, places, outline cards, scene associations and directed/bidirectional relationships. Stable IDs distinguish duplicate names. Legacy facts/style are retained without guessed migration.
- HTTPS OpenAI-compatible chat-completions adapter: short rewrite presets/intensity, explicit selection and opt-in current-scene material preview, 2–3 candidates, source/candidate/difference tabs, manual adoption only. Exact offsets + chapter ID + revision + recovery epoch prevent wrong-instance or stale replacement. Protected-phrase enforcement stays local.
- Preview and import V2/V3 character-card and worldbook JSON as immutable local reference snapshots. Explicit scene bindings and individual AI-context selection; external scripts/prompts are never executed. See REFERENCE_IMPORT.md for limits.
- API key encrypted with Android Keystore AES-GCM, excluded from OS backup and app exports. No bundled key, default provider, telemetry, cloud sync, public sharing, or automatic book rewriting.

## What is intentionally limited

- No real provider has been configured or called. Provider/network/credential end-to-end behavior is unverified. JSON-mode chat-completions compatibility is required; provider-specific APIs may need another adapter. Each generated request can incur the provider's charges.
- AI input is capped at 12,000 combined UTF-16 characters across passage, style, facts, instruction. Candidate length is at most 12,000. Model output is untrusted; facts/continuity cannot be guaranteed.
- The first UI supports browser-shared text, not fetching a URL's article body. A shared link alone must be replaced with copied accessible text. No login/DRM bypass.
- Input files ≤20 MB, expanded ZIP ≤40 MB, ≤2,000 entries; book ≤5,000 chapter chunks. Chapters are split into at most 12,000 UTF-16 characters to keep editing bounded. EPUB images, styling, footnotes/navigation semantics, and complex layouts are flattened to text. EPUB files containing an encryption manifest are conservatively rejected, including some otherwise DRM-free books with obfuscated fonts.
- Backups must fit the same limits on restore. Very large incompressible books may exceed the export budget and produce a clear error rather than an unusable archive.
- Local data is private within the Android app sandbox, but book content is not separately encrypted at rest. Uninstalling the app removes it; explicitly export backups first.
- Edits are saved only with the save/adopt button, not while typing. Candidate sessions are not yet persisted across process death. Draft recovery, multi-block partial adoption, typed continuation/summary and editable structured presets are future increments, not claimed here.
- Host native-render screenshots are not physical-phone acceptance. Accessibility, low-memory behavior, keyboard/IME, file pickers, and provider connection require device validation.

## Build and test

JDK 21; Android SDK 36; Gradle 9.1; Android Gradle Plugin 9.0.1. Dependencies are official AndroidX/Kotlin components. Run:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1

`CoreTest` covers import/storage/backup and malicious archives; `AiTest` covers payload, parsing, endpoint and address policies without external requests; `StoryModelTest` checks exact selection, stale/recovered anchors, opt-in request scope, IDs/scene associations and backup compatibility; `EditingTest` keeps legacy helper/protected-phrase coverage; `ReaderUiTest` exercises native Compose UI using Robolectric/Roborazzi and writes screenshots to `app/build/outputs/reader-ui`.

The repository/publication status is tracked separately from this local build. Do not commit API keys, signing keys, user manuscripts, or private data.

See WRITING_WORKSPACE.md for the stage contract and deferred work. TESTING.md distinguishes the earlier baseline from this increment.

## Version and updates

On the bookshelf, open 检查更新 to view the bundled Chinese release notes. Network checks and GitHub device authorization start only after an explicit tap; no account token is bundled. The updater verifies package, increasing version, ABI, certificate, size and SHA256, then asks Android to install. Private-repository authorization and physical-phone upgrade/data preservation still need device acceptance.

## 云端开发入口

后续开发默认使用云端：[打开开发工作区](https://codespaces.new/h516df/xiayizhang) · [环境、检查命令与迁移边界](CLOUD_DEVELOPMENT.md)。
