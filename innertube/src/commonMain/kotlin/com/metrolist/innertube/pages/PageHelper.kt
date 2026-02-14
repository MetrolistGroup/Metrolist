package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Menu
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.metrolist.innertube.models.Run

object PageHelper {
    // Icon types for library management (YouTube changed these in Feb 2026)
    // Old icons: LIBRARY_ADD (not in library), LIBRARY_SAVED/LIBRARY_REMOVE (in library)
    // New icons: BOOKMARK_BORDER (not in library), BOOKMARK (in library)
    // Note: KEEP/KEEP_OFF are for "Pin to Listen Again" - different from library!
    private val LIBRARY_ADD_ICONS = setOf("LIBRARY_ADD", "BOOKMARK_BORDER")
    private val LIBRARY_SAVED_ICONS = setOf("LIBRARY_SAVED", "BOOKMARK", "LIBRARY_REMOVE")
    private val ALL_LIBRARY_ICONS = LIBRARY_ADD_ICONS + LIBRARY_SAVED_ICONS

    /**
     * Data class to hold both library feedback tokens extracted from a menu
     */
    data class LibraryFeedbackTokens(
        val addToken: String?,      // Token to add song to library (from BOOKMARK_BORDER)
        val removeToken: String?    // Token to remove song from library (from BOOKMARK)
    )

    /**
     * Check if an icon type is a library-related icon (for filtering menu items)
     * Excludes KEEP/KEEP_OFF which are for "Pin to Listen Again"
     */
    fun isLibraryIcon(iconType: String?): Boolean {
        if (iconType == null) return false
        // Exclude KEEP/KEEP_OFF (Listen Again pins)
        if (iconType == "KEEP" || iconType == "KEEP_OFF") return false
        return iconType in ALL_LIBRARY_ICONS || iconType.startsWith("LIBRARY_")
    }

    /**
     * Check if an icon type indicates the song is NOT in library (add state)
     */
    fun isAddLibraryIcon(iconType: String?): Boolean {
        return iconType in LIBRARY_ADD_ICONS
    }

    /**
     * Check if an icon type indicates the song IS in library (saved/remove state)
     */
    fun isSavedLibraryIcon(iconType: String?): Boolean {
        return iconType in LIBRARY_SAVED_ICONS
    }

    fun extractRuns(columns: List<FlexColumn>, typeLike: String): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs = column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                ?: continue

            for (run in runs) {
                val typeStr = run.navigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
                    ?: run.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                    ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }

    /**
     * Extract library feedback tokens from a list of menu items.
     *
     * This function iterates through ALL toggle menu items and extracts tokens
     * based on their icon types, ensuring we don't confuse library tokens with
     * "Pin to Listen Again" tokens (KEEP/KEEP_OFF).
     *
     * YouTube's icon system (Feb 2026):
     * - BOOKMARK_BORDER: Song NOT in library -> defaultToken = ADD, toggledToken = REMOVE
     * - BOOKMARK: Song IS in library -> defaultToken = REMOVE, toggledToken = ADD
     * - KEEP/KEEP_OFF: "Pin to Listen Again" - COMPLETELY DIFFERENT, must be ignored!
     *
     * @param menuItems The list of menu items to search through
     * @return LibraryFeedbackTokens containing both add and remove tokens
     */
    fun extractLibraryTokensFromMenuItems(
        menuItems: List<Menu.MenuRenderer.Item>?
    ): LibraryFeedbackTokens {
        if (menuItems == null) return LibraryFeedbackTokens(null, null)

        var addToken: String? = null
        var removeToken: String? = null

        for (item in menuItems) {
            val toggleRenderer = item.toggleMenuServiceItemRenderer ?: continue
            val iconType = toggleRenderer.defaultIcon.iconType

            // Skip KEEP/KEEP_OFF icons (Pin to Listen Again) - these are NOT library actions
            if (iconType == "KEEP" || iconType == "KEEP_OFF") continue

            // Only process library-related icons
            if (!isLibraryIcon(iconType)) continue

            val defaultToken = toggleRenderer.defaultServiceEndpoint.feedbackEndpoint?.feedbackToken
            val toggledToken = toggleRenderer.toggledServiceEndpoint?.feedbackEndpoint?.feedbackToken

            // Determine which token is which based on icon type
            when {
                isAddLibraryIcon(iconType) -> {
                    // BOOKMARK_BORDER or LIBRARY_ADD: default=add, toggled=remove
                    if (addToken == null) addToken = defaultToken
                    if (removeToken == null) removeToken = toggledToken
                }
                isSavedLibraryIcon(iconType) -> {
                    // BOOKMARK or LIBRARY_SAVED/REMOVE: default=remove, toggled=add
                    if (removeToken == null) removeToken = defaultToken
                    if (addToken == null) addToken = toggledToken
                }
            }
        }

        return LibraryFeedbackTokens(addToken, removeToken)
    }

    /**
     * Extract feedback token for library operations.
     *
     * YouTube's new icon system (Feb 2026):
     * - BOOKMARK_BORDER: Song NOT in library -> defaultToken = ADD, toggledToken = REMOVE
     * - BOOKMARK: Song IS in library -> defaultToken = REMOVE, toggledToken = ADD
     *
     * @param menu The toggle menu renderer containing the feedback tokens
     * @param type "LIBRARY_ADD" to get the add token, "LIBRARY_REMOVE" to get the remove token
     * @return The appropriate feedback token, or null if not found
     */
    fun extractFeedbackToken(menu: Menu.MenuRenderer.Item.ToggleMenuServiceRenderer?, type: String): String? {
        if (menu == null) return null
        val defaultToken = menu.defaultServiceEndpoint.feedbackEndpoint?.feedbackToken
        val toggledToken = menu.toggledServiceEndpoint?.feedbackEndpoint?.feedbackToken
        val iconType = menu.defaultIcon.iconType

        // Determine if the current icon indicates song is NOT in library
        // BOOKMARK_BORDER or LIBRARY_ADD = song is NOT in library (default action is ADD)
        val songNotInLibrary = iconType in LIBRARY_ADD_ICONS

        return when (type) {
            "LIBRARY_ADD" -> {
                // We want the ADD token
                if (songNotInLibrary) {
                    // Icon shows "add" state, default action adds to library
                    defaultToken
                } else {
                    // Icon shows "saved" state, toggled action would add back
                    toggledToken
                }
            }
            "LIBRARY_REMOVE", "LIBRARY_SAVED" -> {
                // We want the REMOVE token
                if (songNotInLibrary) {
                    // Icon shows "add" state, toggled action would remove
                    toggledToken
                } else {
                    // Icon shows "saved" state, default action removes from library
                    defaultToken
                }
            }
            else -> if (iconType == type) defaultToken else toggledToken
        }
    }
}