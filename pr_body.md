## Problem
The artist name in the player gets cut off when the track has an explicit icon, as the text container overflows the Row bounds.

## Cause
The Box containing the artist name was using fillMaxWidth within a Row set to Arrangement.SpaceBetween, causing it to push beyond the available space when the explicit icon is present.

## Solution
Changed the Row horizontalArrangement to spacedBy and replaced fillMaxWidth with weight on the artist Box to ensure it only occupies available space. Applied this fix to both Player and MiniPlayer to prevent regressions.

## Testing
Verified the player layout rendering with explicit tracks to ensure the artist text properly fits within the bounds and aligns correctly with the explicit icon without requiring placeholders.

## Related Issues
Closes #4124
