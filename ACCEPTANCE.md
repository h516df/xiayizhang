# Device acceptance checklist

Use synthetic text only until persistence/import/export has passed on the intended phone.

1. Install debug APK, open empty bookshelf; import UTF-8 TXT and GB18030 TXT with Chinese chapter headings.
2. Import DRM-free EPUB with nonalphabetical spine order; confirm chapter order and that scripts/styles do not appear or execute.
3. Edit one chapter, wait a second, background/foreground, force-stop/reopen; verify saved text and unchanged exported original bytes.
4. Open version history, restore an earlier version, then restore the pre-rollback version.
5. Export `.prbook` through Android's system file picker, restore it, verify independent copy, original and revisions.
6. Share text from browser into the app; verify preview before import. A URL-only share is not article extraction.
7. Configure an authorized HTTPS provider and key directly on device. Confirm destination, model, selected content and billing before each first request. Do not send real private writing until this is approved.
8. Generate 2–3 candidates; dismiss without change; retry and manually accept; verify changed span, protected phrases, and rollback.
9. Test offline, invalid key, refused output, HTTP error, malformed JSON and oversized response; none should alter text.
10. Test 1.5x font, keyboard, rotation/back, denied/cancelled import/export, repeated taps, low storage, and process recreation.
