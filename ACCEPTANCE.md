# Device acceptance checklist

Use synthetic text until persistence/import/export passes on the intended phone. Host Compose tests are not device acceptance.

1. Import UTF-8/GB18030 TXT and DRM-free EPUB; check order, inert scripts, chapter navigation and font size.
2. Scroll mid-chapter, long-press/drag a selection including a repeated phrase. Own edit must change exactly that occurrence. Cancel/save/AI adoption must return to the reading position.
3. Try IME, 1.5x font, small viewport, rotation, Back and rapid repeated taps. Cancel is not autosave. Unsaved drafts/candidates may not survive process death.
4. Create/edit same-name characters, places and outline cards. Associate multiple scenes. Add directed/bidirectional relations between correct IDs and edit applicability.
5. Create a card from a selection; edit the source and verify its anchor is marked invalid rather than navigating to a duplicate sentence.
6. Adopt and undo; change the stored chapter and try an older candidate/undo. It must refuse. Recover an old version and verify prior anchors never revive.
7. Export/restore a .prbook using the system picker; verify independent book, original bytes, references, relationships and history. Test a baseline version-1 backup too.
8. Configure an authorized provider directly on device. Confirm model, destination, selection and optional material before sending. No real private writing without approval.
9. Generate/review/cancel/adopt. Verify default payload has only selection/instruction; optional context/cards are removable; future scenes and recursively related material are absent. No automatic setting updates.
10. Check offline/errors/invalid key/oversized or malformed response and interruption. Failure must retain the draft and never write prose. Confirm no configured service gives no generation illusion.
11. Denied/cancelled pickers, low storage, backgrounding, and process recreation require device validation. Keep external backups before using unique manuscripts.

## Local reference JSON import

12. Pick valid V2/V3 character and worldbook JSON; cancel the preview and verify no resource/history write. Confirm twice using separate previews: same names must remain separate resources.
13. Verify malformed/oversized JSON fails without changing the book. PNG/CHARX must not appear supported. External URLs/scripts must never run or load automatically.
14. Bind an import to one scene, disable it, and check the AI picker only lists enabled current-scene entries. Selecting one entry must not silently include other entries, opening dialogue or system prompts.
15. Copy an entry into editable local material, save edits, and verify the original JSON snapshot is unchanged. Restore a backup as a separate book/workspace and verify exact raw JSON, bindings and edited cards survive.
16. Check the complete reader 更多 menu and import preview/context picker on small screens and large fonts; system file selection remains a physical-device acceptance step.
