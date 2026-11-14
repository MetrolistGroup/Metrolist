@file:Suppress("ktlint:standard:max-line-length")

package com.metrolist.shared.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Material Symbols used throughout the Android, shared, and desktop UI. */
object MetrolistIcons {
    val Filled: MetrolistIconsFilled = MetrolistIconsFilled
    val Default: MetrolistIconsFilled = MetrolistIconsFilled
    val Outlined: MetrolistIconsOutlined = MetrolistIconsOutlined
    val Rounded: MetrolistIconsRounded = MetrolistIconsRounded

    object AutoMirrored {
        object Filled {
            val ArrowBack: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.ArrowBack",
                    pathData = "m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z",
                    autoMirror = true,
                )
            }

            val ArrowForward: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.ArrowForward",
                    pathData = "M647-440H160v-80h487L423-744l57-56 320 320-320 320-57-56 224-224Z",
                    autoMirror = true,
                )
            }

            val KeyboardArrowLeft: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.KeyboardArrowLeft",
                    pathData = "M560-240 320-480l240-240 56 56-184 184 184 184-56 56Z",
                    autoMirror = true,
                )
            }

            val KeyboardArrowRight: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.KeyboardArrowRight",
                    pathData = "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z",
                    autoMirror = true,
                )
            }

            val List: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.List",
                    pathData = "M280-600v-80h560v80H280Zm0 160v-80h560v80H280Zm0 160v-80h560v80H280ZM160-600q-17 0-28.5-11.5T120-640q0-17 11.5-28.5T160-680q17 0 28.5 11.5T200-640q0 17-11.5 28.5T160-600Zm0 160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520q17 0 28.5 11.5T200-480q0 17-11.5 28.5T160-440Zm0 160q-17 0-28.5-11.5T120-320q0-17 11.5-28.5T160-360q17 0 28.5 11.5T200-320q0 17-11.5 28.5T160-280Z",
                    autoMirror = true,
                )
            }

            val Login: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.Login",
                    pathData = "M480-120v-80h280v-560H480v-80h280q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H480Zm-80-160-55-58 102-102H120v-80h327L345-622l55-58 200 200-200 200Z",
                    autoMirror = true,
                )
            }

            val Logout: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.Logout",
                    pathData = "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h280v80H200v560h280v80H200Zm440-160-55-58 102-102H360v-80h327L585-622l55-58 200 200-200 200Z",
                    autoMirror = true,
                )
            }

            val MenuOpen: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.MenuOpen",
                    pathData = "M120-240v-80h520v80H120Zm664-40L584-480l200-200 56 56-144 144 144 144-56 56ZM120-440v-80h400v80H120Zm0-200v-80h520v80H120Z",
                    autoMirror = true,
                )
            }

            val OpenInNew: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.OpenInNew",
                    pathData = "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h280v80H200v560h560v-280h80v280q0 33-23.5 56.5T760-120H200Zm188-212-56-56 372-372H560v-80h280v280h-80v-144L388-332Z",
                    autoMirror = true,
                )
            }

            val PlaylistAdd: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.PlaylistAdd",
                    pathData = "M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z",
                    autoMirror = true,
                )
            }

            val PlaylistPlay: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.PlaylistPlay",
                    pathData = "M120-320v-80h320v80H120Zm0-160v-80h480v80H120Zm0-160v-80h480v80H120Zm520 520v-320l240 160-240 160Z",
                    autoMirror = true,
                )
            }

            val QueueMusic: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.QueueMusic",
                    pathData = "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-328h200v80H760v360q0 50-35 85t-85 35ZM120-320v-80h320v80H120Zm0-160v-80h480v80H120Zm0-160v-80h480v80H120Z",
                    autoMirror = true,
                )
            }

            val VolumeDown: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.VolumeDown",
                    pathData = "M200-360v-240h160l200-200v640L360-360H200Zm440 40v-322q45 21 72.5 65t27.5 97q0 53-27.5 96T640-320Z",
                    autoMirror = true,
                )
            }

            val VolumeMute: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.VolumeMute",
                    pathData = "M280-360v-240h160l200-200v640L440-360H280Z",
                    autoMirror = true,
                )
            }

            val VolumeOff: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.VolumeOff",
                    pathData = "M792-56 671-177q-25 16-53 27.5T560-131v-82q14-5 27.5-10t25.5-12L480-368v208L280-360H120v-240h128L56-792l56-56 736 736-56 56Zm-8-232-58-58q17-31 25.5-65t8.5-70q0-94-55-168T560-749v-82q124 28 202 125.5T840-481q0 53-14.5 102T784-288ZM650-422l-90-90v-130q47 22 73.5 66t26.5 96q0 15-2.5 29.5T650-422ZM480-592 376-696l104-104v208Z",
                    autoMirror = true,
                )
            }

            val VolumeUp: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Filled.VolumeUp",
                    pathData = "M560-131v-82q90-26 145-100t55-168q0-94-55-168T560-749v-82q124 28 202 125.5T840-481q0 127-78 224.5T560-131ZM120-360v-240h160l200-200v640L280-360H120Zm440 40v-322q47 22 73.5 66t26.5 96q0 51-26.5 94.5T560-320Z",
                    autoMirror = true,
                )
            }
        }

        object Outlined {
            val FormatListBulleted: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.FormatListBulleted",
                    pathData = "M360-200v-80h480v80H360Zm0-240v-80h480v80H360Zm0-240v-80h480v80H360ZM200-160q-33 0-56.5-23.5T120-240q0-33 23.5-56.5T200-320q33 0 56.5 23.5T280-240q0 33-23.5 56.5T200-160Zm0-240q-33 0-56.5-23.5T120-480q0-33 23.5-56.5T200-560q33 0 56.5 23.5T280-480q0 33-23.5 56.5T200-400Zm0-240q-33 0-56.5-23.5T120-720q0-33 23.5-56.5T200-800q33 0 56.5 23.5T280-720q0 33-23.5 56.5T200-640Z",
                    autoMirror = true,
                )
            }

            val List: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.List",
                    pathData = "M280-600v-80h560v80H280Zm0 160v-80h560v80H280Zm0 160v-80h560v80H280ZM160-600q-17 0-28.5-11.5T120-640q0-17 11.5-28.5T160-680q17 0 28.5 11.5T200-640q0 17-11.5 28.5T160-600Zm0 160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520q17 0 28.5 11.5T200-480q0 17-11.5 28.5T160-440Zm0 160q-17 0-28.5-11.5T120-320q0-17 11.5-28.5T160-360q17 0 28.5 11.5T200-320q0 17-11.5 28.5T160-280Z",
                    autoMirror = true,
                )
            }

            val Login: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.Login",
                    pathData = "M480-120v-80h280v-560H480v-80h280q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H480Zm-80-160-55-58 102-102H120v-80h327L345-622l55-58 200 200-200 200Z",
                    autoMirror = true,
                )
            }

            val Logout: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.Logout",
                    pathData = "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h280v80H200v560h280v80H200Zm440-160-55-58 102-102H360v-80h327L585-622l55-58 200 200-200 200Z",
                    autoMirror = true,
                )
            }

            val NavigateNext: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.NavigateNext",
                    pathData = "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z",
                    autoMirror = true,
                )
            }

            val PlaylistAdd: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.PlaylistAdd",
                    pathData = "M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z",
                    autoMirror = true,
                )
            }

            val QueueMusic: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.QueueMusic",
                    pathData = "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-328h200v80H760v360q0 50-35 85t-85 35ZM120-320v-80h320v80H120Zm0-160v-80h480v80H120Zm0-160v-80h480v80H120Z",
                    autoMirror = true,
                )
            }

            val TrendingUp: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.TrendingUp",
                    pathData = "m136-240-56-56 296-298 160 160 208-206H640v-80h240v240h-80v-104L536-320 376-480 136-240Z",
                    autoMirror = true,
                )
            }

            val VolumeDown: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.VolumeDown",
                    pathData = "M200-360v-240h160l200-200v640L360-360H200Zm440 40v-322q45 21 72.5 65t27.5 97q0 53-27.5 96T640-320ZM480-606l-86 86H280v80h114l86 86v-252ZM380-480Z",
                    autoMirror = true,
                )
            }

            val VolumeMute: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.VolumeMute",
                    pathData = "M280-360v-240h160l200-200v640L440-360H280Zm80-80h114l86 86v-252l-86 86H360v80Zm100-40Z",
                    autoMirror = true,
                )
            }

            val VolumeOff: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.VolumeOff",
                    pathData = "M792-56 671-177q-25 16-53 27.5T560-131v-82q14-5 27.5-10t25.5-12L480-368v208L280-360H120v-240h128L56-792l56-56 736 736-56 56Zm-8-232-58-58q17-31 25.5-65t8.5-70q0-94-55-168T560-749v-82q124 28 202 125.5T840-481q0 53-14.5 102T784-288ZM650-422l-90-90v-130q47 22 73.5 66t26.5 96q0 15-2.5 29.5T650-422ZM480-592 376-696l104-104v208Zm-80 238v-94l-72-72H200v80h114l86 86Zm-36-130Z",
                    autoMirror = true,
                )
            }

            val VolumeUp: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Outlined.VolumeUp",
                    pathData = "M560-131v-82q90-26 145-100t55-168q0-94-55-168T560-749v-82q124 28 202 125.5T840-481q0 127-78 224.5T560-131ZM120-360v-240h160l200-200v640L280-360H120Zm440 40v-322q47 22 73.5 66t26.5 96q0 51-26.5 94.5T560-320ZM400-606l-86 86H200v80h114l86 86v-252ZM300-480Z",
                    autoMirror = true,
                )
            }
        }

        object Rounded {
            val QueueMusic: ImageVector by lazy {
                materialSymbol(
                    name = "AutoMirrored.Rounded.QueueMusic",
                    pathData = "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-288q0-17 11.5-28.5T720-720h120q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640h-80v360q0 50-35 85t-85 35ZM160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Z",
                    autoMirror = true,
                )
            }
        }
    }
}
