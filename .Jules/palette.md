# Palette's Journal 🎨

## 2024-05-23 - URL Input Usability
**Learning:** Text fields for URLs or complex inputs significantly benefit from a "Clear" button and keyboard submit actions. This reduces friction for users pasting wrong links or wanting to quickly add items without reaching for a button.
**Action:** Always include `trailingIcon` with a clear action and `keyboardOptions` with `ImeAction.Go`/`Done` for primary input fields.

## 2024-05-24 - Interactive Item Feedback
**Learning:** Using `pointerInput` with `detectTapGestures` handles taps but sacrifices built-in accessibility semantics and visual feedback (ripples). This makes the app feel "dead" to touch and inaccessible to screen readers.
**Action:** Always use `combinedClickable` (with `@OptIn(ExperimentalFoundationApi::class)`) for elements requiring both click and long-click interactions to ensure ripples and a11y support.

## 2024-05-25 - Safety in Long Press Actions
**Learning:** Using long-press for immediate destructive actions (delete) is dangerous and error-prone. Users expect long-press to provide options or selection context, not instant data loss.
**Action:** Map long-press interactions to "Selection Mode" or a context menu, never directly to delete.
