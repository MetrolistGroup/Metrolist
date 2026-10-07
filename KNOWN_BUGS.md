# 7xTune — Known Bugs

This file tracks known bugs and edge cases that are intentionally documented before a fix is implemented.

## KB-001 — Quick Picks can be hidden for users with no listening history

**Status:** Known / not fixed

**Severity:** Medium

**Area:** Home screen → Quick Picks

### Summary

On a fresh/new user account with little or no listening history, the **Quick Picks** shelf can remain hidden even though the rest of the Home screen loads normally.

The issue appears to resolve itself after the user listens to roughly **1–2 songs** and the app gets some listening history. After that, Quick Picks can populate normally.

### Reproduction

1. Start with a new/empty local listening history.
2. Open the Home screen.
3. Do not play any songs.
4. Refresh/reopen Home as needed.
5. Observe that the Quick Picks shelf may not appear.
6. Play 1–2 songs.
7. Return to Home / refresh.
8. Quick Picks is then able to appear/populate.

### Expected behavior

Quick Picks should have a safe first-run/empty-history behavior. A user should not have to play songs before the shelf becomes visible.

For a user with no history, the app should either:
- show suitable fallback recommendations, or
- show a clearly defined empty-state/shelf rather than silently removing Quick Picks.

### Current behavior

The first load can produce no usable Quick Picks items when there is no history.

The current recommendation pipeline depends heavily on local listening data and the recent-song seed for its enrichment path. With no history, those sources can all be empty. The Home UI also decides whether the dedicated Quick Picks section is authoritative based on whether usable dedicated content exists.

### Why this matters

This is most noticeable for first-time users. It can make Quick Picks look broken when the underlying app is actually waiting for enough user activity to generate recommendations.

### Workaround

Play 1–2 songs and return to/refresh the Home screen. Once listening history exists, Quick Picks can populate.

### Investigation notes

Relevant code paths on the current baseline include:

- `HomeViewModel.getQuickPicks()`
- `QuickPicksLoader`
- Home screen Quick Picks section selection/order logic in `HomeScreen.kt`
- The `latestEvent()` / recent-listening based enrichment path

The correct long-term fix should preserve the current refresh reliability improvements while adding a deterministic **no-history fallback**. Avoid reintroducing the previous regression where Quick Picks and Speed Dial disappeared during refresh.

### Regression guard

Any future fix should verify both cases:

1. **Fresh/no-history user:** Quick Picks is visible or has an explicit fallback state.
2. **Existing-history user:** Quick Picks keeps its current behavior, ordering, refresh stability, and performance.
