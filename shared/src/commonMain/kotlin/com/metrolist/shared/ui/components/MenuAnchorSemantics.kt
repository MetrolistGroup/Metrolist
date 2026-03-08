package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.collapsed
import com.metrolist.shared.generated.resources.expanded
import org.jetbrains.compose.resources.stringResource

@Composable
fun Modifier.menuAnchorSemantics(expanded: Boolean): Modifier {
    val menuState = stringResource(if (expanded) Res.string.expanded else Res.string.collapsed)
    return semantics {
        role = Role.DropdownList
        stateDescription = menuState
    }
}
