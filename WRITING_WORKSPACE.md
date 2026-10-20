# Writing workspace, stages 2a–2b

This increment replaces the chapter-copy AI form with a selection-first writing workflow. It is not the completed writing product.

## Implemented contract

- Read without completing project metadata. Long-press a real read-only text selection, then choose 自己改 / AI 改 / 存为资料. Offsets use Android's UTF-16 selection coordinates, not first matching substring.
- The reader remains mounted behind editing dialogs. Applying/cancelling returns to the same scroll state, with text-length clamping if necessary. Position is also saved on navigation. Cancel writes nothing.
- Edits carry chapter ID, revision, recovery epoch, exact range and source text. Store atomically reloads and validates before replacing; stale candidates cannot be pasted into a newer version. One-step undo also checks the exact last applied revision before restoring. Immutable source and revision history remain intact.
- Three real entrances: 正文 / 大纲 / 资料. Create and edit characters, places and outline cards; associate cards with chapter scenes. Create/edit directed or bidirectional character relationships with scene applicability. IDs, not names, identify entities. The initial structure deliberately has one scene per imported chapter.
- Selection-created cards retain a source anchor. Changed source versions show the anchor as invalid instead of seeking a matching sentence. No automatic extraction or setting changes on prose adoption.
- AI offers three rewrite presets, intensity, and optional free instruction. Only selection plus visible instruction is sent by default. Current-scene cards, relationships, nearby context, and legacy facts/style are opt-in and previewed. Nothing recursively includes the entire book or future scenes.
- Candidate views switch between source / candidate / changed envelope for a phone-width layout. Explicit adoption only. Unconfigured services never generate fake candidates. Test fixtures are injected only by tests.
- Version-1 backup envelope remains compatible; missing chapter and scene IDs are deterministic on legacy decode. Legacy facts/style remain unstructured, labelled 原有设定. Provider settings and keys stay outside book data/backups.

## Local reference increment

Stage 2b adds bounded character/worldbook JSON preview, immutable local snapshots, explicit scene bindings and individual opt-in AI context. It also replaces the cramped reader toolbar with a complete 更多 menu and aligns selected chips with the ink-green theme. See REFERENCE_IMPORT.md; this is basic data import, not full Tavern runtime compatibility.

## Next increments, not claimed as done

1. Multi-block diffs and partial adoption with per-block base-version anchors (current diff is one changed envelope).
2. Persistent candidate sessions, request/draft recovery after process death, and a candidate history shelf (current candidates live only in the open session).
3. Editable structured presets and separate typed rewrite / continuation / summary protocols. The current action is explicitly rewrite only; it does not claim continuation or summary support.
4. Scene splitting/reordering and larger-project navigation, source-anchor relocation with explicit review, more comprehensive small-screen/accessibility/device QA.

## Verification boundary

All manuscript fixtures are synthetic. No user manuscript was sent to any service, no API key was searched for or reused, and no billable AI request was made. Native Compose host checks are not physical-device verification. See TESTING.md for the actual latest check status.

## Inspiration and licensing

The public interactions of Novel, Pagekin (formerly AI Novel Writer), and Novelcrafter informed workflow discussion, not code incorporation. No GPL/AGPL source, Electron/React migration, or new diff library is included. Existing Kotlin/Compose architecture remains in use. Source-only publication excludes APKs, screenshots, manuscripts, and credentials.
