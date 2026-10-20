# Local reference JSON import, stage 2b

This increment builds on selection-first writing. It imports basic character/lore material, not a Tavern-compatible runtime or a Linux execution workspace.

## Available workflow

Under 资料, choose 导入 JSON 资料. A bounded preview shows format, name, entry counts and compatibility warnings. Cancel leaves storage unchanged. Confirm creates a separate immutable snapshot in this book's local workspace and binds it to the current scene. Identical names remain separate UUIDs.

Supported inputs are Character Card V2/V3 JSON, embedded character_book entries, standalone CharacterBook JSON and SillyTavern world-info JSON (object-shaped entries). V3 metadata/assets are preserved but not fetched. PNG cards and CHARX are explicitly unsupported in this increment.

Open an imported item to inspect its text-only preview, enable/disable its binding or choose applicable scenes. Copy basic character text or an enabled lore entry into an editable story-card draft. Saving that draft does not modify the imported snapshot. Oversized drafts are rejected rather than silently truncated.

AI rewriting still sends only selected prose/instruction by default. In 更多, explicitly choose individual imported basic-character/lore parts from enabled current-scene bindings. The complete composed request remains visible before sending. Disabled entries, other scenes and other books are unavailable. A request exceeding the existing context budget cannot be submitted; it is never silently truncated.

## Preserved but not executed

Original JSON, unknown fields, extensions, external IDs and character/worldbook settings survive backup/restore. Keys, constant flags, ordering, selective matching, regex, recursion, scripts, URLs, assets, external system_prompt/post_history_instructions and opening/example dialogue do not become app instructions or execute automatically. Imported basic description/personality/scenario and selected lore content are untrusted reference text, not tool permissions. No network fetch occurs during import.

The app's workspace UUID is independent of book/resource names. Backup restore creates a new book and workspace; referenced snapshots remain local copies. This slice has no global shared library, live updates or cross-workspace binding.

## Bounds and recovery

JSON input: 4 MiB; individual strings: 128 KiB; nesting: 32; lore entries: 2,000; resources: 200; combined original reference JSON: 16 MiB. Existing book and backup limits also apply. Invalid UTF-8, duplicate JSON keys, invalid surrogates, malformed syntax and invalid normalized bindings are rejected. Restoration checks raw-JSON hashes and reparses the preserved source rather than trusting edited normalized fields. Missing new fields in old backups remain compatible; malformed present fields are rejected.

Visual previews deliberately abbreviate long strings and label this; stored originals are unchanged. Large entry lists and the AI reference picker render lazily.

## Not yet included

PNG/CHARX import, export back to external card formats, complete Tavern trigger semantics, editable imported snapshots/version tracking, shared global libraries, local Linux/tool execution, persistent AI candidate sessions and multi-block partial adoption remain later increments. Real provider calls, physical-device file pickers and hardware selection/IME behavior are not verified by host fixtures.
