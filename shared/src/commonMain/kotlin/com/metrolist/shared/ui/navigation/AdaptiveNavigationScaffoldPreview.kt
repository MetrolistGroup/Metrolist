package com.metrolist.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.filter_library
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Preview(
    name = "Phone · compact",
    group = "Adaptive navigation",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun PhoneAdaptiveNavigationPreview() {
    AdaptiveNavigationPreview(AdaptiveFormFactor.TOUCH)
}

@Preview(
    name = "Tablet · expanded",
    group = "Adaptive navigation",
    widthDp = 840,
    heightDp = 1180,
    showBackground = true,
)
@Composable
private fun TabletAdaptiveNavigationPreview() {
    AdaptiveNavigationPreview(AdaptiveFormFactor.TOUCH)
}

@Preview(
    name = "Desktop · large",
    group = "Adaptive navigation",
    widthDp = 1440,
    heightDp = 900,
    showBackground = true,
)
@Composable
private fun DesktopAdaptiveNavigationPreview() {
    AdaptiveNavigationPreview(AdaptiveFormFactor.DESKTOP)
}

@Preview(
    name = "TV · expanded",
    group = "Adaptive navigation",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun TvAdaptiveNavigationPreview() {
    AdaptiveNavigationPreview(AdaptiveFormFactor.TELEVISION)
}

@Preview(
    name = "Automotive · medium",
    group = "Adaptive navigation",
    widthDp = 800,
    heightDp = 480,
    showBackground = true,
)
@Composable
private fun AutomotiveAdaptiveNavigationPreview() {
    AdaptiveNavigationPreview(AdaptiveFormFactor.AUTOMOTIVE)
}

@Composable
private fun AdaptiveNavigationPreview(formFactor: AdaptiveFormFactor) {
    MaterialTheme {
        val layout = currentAdaptiveLayout(formFactor)
        val items =
            listOf(
                AdaptiveNavigationItem(
                    key = "home",
                    label = stringResource(Res.string.home),
                    icon = MetrolistIcons.Outlined.Home,
                    selectedIcon = MetrolistIcons.Filled.Home,
                ),
                AdaptiveNavigationItem(
                    key = "search",
                    label = stringResource(Res.string.search),
                    icon = MetrolistIcons.Outlined.Search,
                    selectedIcon = MetrolistIcons.Filled.Search,
                ),
                AdaptiveNavigationItem(
                    key = "library",
                    label = stringResource(Res.string.filter_library),
                    icon = MetrolistIcons.Outlined.LibraryMusic,
                    selectedIcon = MetrolistIcons.Filled.LibraryMusic,
                ),
            )
        AdaptiveNavigationScaffold(
            layout = layout,
            items = items,
            selectedKey = "home",
            onSelect = {},
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
    }
}
