# ShiftWatch v20.5 — Recognition & review-state integrity

- One automatic start-time authority: solved, non-ambiguous physical block only. Legacy local/time-vocabulary fallbacks are no longer permitted to create automatic handwritten shifts. Explicit manual time selection is retained.
- Reject time-atlas hints whose source pixels clearly belong to another physical block.
- Chronological block-order correction now requires its alternative to have independent multi-day and atlas support.
- Remove costly time inference from the canvas marker render and review footer. Unconfirmed blue matches never advertise an inferred time or count as resolved.
- Start a new image with new marker state and expose row diagnostics on demand to avoid overlaying the photograph with permanent debug text.
- Date safety: partial confidence alone cannot mark a fallback week ready; an authoritative header or user-confirmed week is required.
- Existing manual shift editing/removal, identity-learning and persistence workflows remain available.

Validation: isolated Kotlin engine compilation and regression harness; Android Studio Gradle build still required before installing.
- Identity bootstrap uses one sequential coroutine instead of racing independent saved-profile and OCR visual scans. Seed and marker state is reset on a genuinely new image. Cancellation does not publish late visual matches.
- Explicitly tapped time labels must be in the same structural block's label corner; unrelated nearest-time, canonical-row and historical semantic shortcuts cannot silently confirm a tap.
- Compact viewer footer counts confirmed times separately from identity suggestions. Optional diagnostics show a blue marker's geometric block ID for debugging wrong-row ownership.
