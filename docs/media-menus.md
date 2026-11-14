# Media menus

## Models and hosts

Shared definitions live in `shared/src/commonMain/kotlin/com/metrolist/shared/ui/menu/`. `MediaMenuModel.kt` defines item menus. `SongMenuActions.kt` supplies common song/player actions. Hosts provide playback, navigation, downloads, sharing and dialogs. Missing callbacks hide unavailable actions.

`MediaMenuPlaybackActions.kt` shares collection playback and queue insertion for desktop and the mobile host. Keep loaders and error presentation in host adapters. Preserve duplicate occurrences, cancellation and the existing queue when loading fails.

Android queue ordering uses `QueueMediaMenuModel.kt` and `QueueMenuSheetContent.kt`. iOS uses shared mobile song menus; desktop queue hosts supply occurrence-specific callbacks. The Swift host mounts `MobileApp`, whose item and player menus live in common code; it defines no separate media menus. TV queue controls also live in common code with their remote-control interaction model. Lyrics editors, artwork-card overflow entry points and account menus are separate from these models.

## Capabilities

Unavailable episodes remain visible for management and sharing. Disable playback, queue insertion and new downloads while keeping removal of existing downloads.

Keep downloads, storage permissions, confirmation, native sharing, clipboard modifiers, file export and System equalizer as platform callbacks. Guests cannot start radio or bypass host transport restrictions.

Android and iOS song, queue and player menus use the shared player session for dislikes. Disliking the current song invokes the player's Next command, including room restrictions and remote playback routing. Removing a dislike or disliking another song does not advance playback. Queue occurrences stay in place.

Song, queue and player menus share the confirmed Delete upload action for signed-in users. It reads stored upload metadata for rows built from downloads or incomplete queue metadata and fetches the complete uploads list if the entity ID is missing. Deletion clears library membership while keeping local audio, history and playlist entries; download removal and playlist removal have their own actions.

The shared mobile playlist menu offers local hiding for remote and stored playlists. Hiding keeps the playlist on YouTube Music and uses the hidden-library restore setting. Built-in collections and history playlists have their own menus.

Built-in collections use the Speed Dial pin action and playlist rows: Liked songs, Downloaded, My top 100, Cached, Uploaded, New episodes, Episodes for later, History, and the weekly and monthly history playlists. Shortcut IDs resolve to their Library destinations. Tiles resolve titles from resources and use collection covers or icons, including for empty collections and signed-out users. Account playlist aliases share pin state with the corresponding shortcut. Desktop Home shows these pins in Speed Dial and excludes library and local sections elsewhere.

## Presentation

| Surface | Difference and reason |
| --- | --- |
| Item menus | Android chooses a sheet or cursor menu from the input source. Desktop uses cursor menus. iOS uses sheets. All use shared item models and renderers. |
| Android player | Owns volume, video resolution, system equalizer, notification permission, downloads and Toast integration. Its primary row includes radio and its navigation group includes song details. |
| Desktop player | Owns window queue, lyrics and fullscreen controls, Shift-sharing, sleep-timer cancellation and desktop download integration. Its primary row has radio, playlist and share actions. Radio uses the playback controller, which forwards Device Connect commands and protects guest playback; unavailable songs disable it. Edit uses the song-row metadata editor, including its busy and failure states. Details are in the audio/settings group. |
| iOS player | Uses the shared mobile song model, including the like header and radio action. View artist, View album and Song details precede downloads, dislike and block actions, matching Android. Equalizer, tempo and pitch and room navigation follow. Sleep timer is in the menu and also in the main player and queue, using the same playback session. The tempo and pitch action uses Playback speed when varispeed is enabled. |
| Queue menus | Android uses queue-first ordering, a duration header, a download-only action and room navigation. iOS uses song-menu ordering and download removal. Desktop uses occurrence removal callbacks supplied by the queue host. |
| Collection loading | Android's Media3 launchers also manage queue titles and album/playlist loading. Desktop resolves auto collections and stored membership; mobile uses its database and remote loaders. These stay host adapters. |
| Episode and library-only menus | Desktop has saved-episode and compact library management actions with no matching mobile menu. Their model composition stays in the desktop host. |
| Native actions | Download permissions, storage deletion/confirmation, native sharing, clipboard modifiers, file export and system equalizer launch are platform callbacks. |

In TV playlist reorder mode, holding a song row or pressing Menu opens that row's Move actions.

Desktop menu hosts capture presses with the shared `contextMenuAnchor` modifier, which converts local pointer positions to window coordinates before child click handlers run. Every menu state a host opens must use that capture, including separate header and item states. Artist shelves share one item state for songs, videos, albums, playlists, related artists, podcasts and episodes.

Desktop media, video, podcast episode and lyrics-panel menus share `CursorDropdownMenu` and stock Material dropdown motion. Hosts keep the selected item while dismissing so Material can finish its exit. Right-click menus start at the cursor and flip at window edges. Button menus use the trigger bounds captured by `navigationPopupSource`, leave an 8 dp gap, and open below or flip above. Tall button menus scroll within the larger space above or below the trigger. The player More menu opens beside its button, uses the window height and level-3 tonal elevation, flips sides at window edges and falls back above or below when neither side fits.

The player menu opens the compact shared Equalizer dialog on Android, desktop and iOS. Android phones and the shared mobile host close the player menu first. The dialog selects or disables saved profiles, confirms deletion and offers AutoEq and file import where a file picker exists. Android's System equalizer action lives in this dialog and uses the current audio session. MetroEQ and each profile's Edit in MetroEQ action open the full editor. On phones and the shared mobile host, the editor is `MetroEqSheet`, with a single scrim, a drag handle and the animated Tempo and pitch page with predictive Back. Desktop and Android TV use the full editor dialog. Closing the editor returns to the player. Settings opens the full MetroEQ page directly. Android's direct Tempo and pitch menu entry animates in both directions.

On Android and desktop, About Artist photos open a full-screen viewer with Share and Download actions and a link to the photo's Last.fm page when available. These actions belong to the viewer. The shared iOS host shows the gallery only.

## Collection headers

Album and playlist pages show a connected playback ButtonGroup below the header metadata at compact, medium and wide widths. Shuffle is on the left and Play all on the right. Both have an icon and label, split the row width equally through weights and ellipsize long labels. Android, desktop and the shared mobile host show a Like toggle at the end of album and remote non-editable playlist headers, using the same bookmark state, sync and error feedback as the menu. Built-in collections and local or editable playlists omit Like. Playback buttons disappear when the displayed song list is empty. Wide headers scroll independently.

Playlist pages show one cover. Shared mobile playlists use the same sort, direction, reorder and search controls as Android and desktop. Editable mobile playlists use Android's shared Add song content, including duplicate confirmation, inside the mobile sheet host. Item song menus expose Details for the selected song, even when it is not playing.

Album and playlist pages have no playback FABs. Editable playlists have an Add song FAB. It collapses its label on forward scrolling and hides near the list end or during search. It uses the host's player and navigation padding and stays inside the desktop content pane.

Back, More and cover editing sit in the top bar. Like and Remove from library are also in the top-bar menu, and desktop header right-click opens that menu. Search sits at the right end of the sort row, aligned with the artwork and title card. Download, download progress, removal, editing and other collection actions are in the menus.

Collection margins are 16 dp in single-column layouts and 24 dp at the outer edges of the wide layout. Background loading stays top-centered without shifting the header or tracks.

The Add to playlist chooser defaults to Library order, following the Playlists filter's sort and direction, including manual order and play time. Manual follows the saved library positions without reversing them. A saved picker sort overrides the default until the user chooses Library order.

## Selection mode and actions

Album, playlist, auto-playlist and History selection replace the top row with `SelectionTopBar`; there is no floating bottom toolbar. Close, Select all and More stay in the header, and More opens the shared `SongSelectionMenuSheet`.

Queue keeps its rounded header container, shape and dimensions. During selection, the selected count and selection controls replace the playlist name and lock. Its bottom controls switch from sleep timer, shuffle, repeat and collapse to selection actions and More; More exposes the full action set. Queue actions keep occurrence-based playback and removal rules, and TV has its own remote-control selection menu. Guest restrictions, download progress and removal confirmations use the host callbacks. History offers deletion only.

## Song details

`SongDetailsContent.kt` supplies shared detail content; Android and shared mobile hosts provide their sheet containers. Keep format, source and audio details tied to the active playback instance rather than stale UI metadata.

## Verification

`MediaMenuPlaybackActionsTest` covers ordered album/playlist playback, artist shuffle, shuffled duplicate occurrences, queue insertion, empty loads, failures and cancellation without a UI harness.

Run affected [platform checks](checks.md#platform-checks). Compile and exercise iOS on macOS. Check each changed host with available and unavailable items, guests, local audio and duplicate queue entries. Compare song, album, artist, playlist, queue and player menus with and without a playing track, including missing album/artist IDs, like/dislike, download progress, alternate sharing and playlist picker dismissal. Test touch, mouse, TV focus, keyboard and screen-reader actions, dismissal, failed loading and native callbacks on the target platform.

Explicit-song headers must keep the badge visible after short, long and missing artist names, linked artists, large text and RTL. Desktop player overflow must show grouped rows and active checkmarks for connected devices, Cast, video, mini-player and fullscreen, including narrow windows.

For desktop placement, check right-click and button menus after scrolling, with the sidebar visible, at different display scales and near window edges. Right-click menus start at the cursor and stay inside the window; button menus leave the trigger visible. Check motion, scrolling, outside-click and Escape dismissal, keyboard activation, RTL and reduced motion.

For collection headers and selection, check Liked Music, an empty playlist, a remote playlist, an editable playlist, an album, Downloads, Cache, History and Queue at phone, medium and two-pane widths, with long translations, large fonts, RTL and animations disabled.
