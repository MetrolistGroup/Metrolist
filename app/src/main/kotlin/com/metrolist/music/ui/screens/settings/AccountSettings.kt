/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.App.Companion.forgetAccount
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.UseLoginForBrowse
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.ReleaseNotesCard
import com.metrolist.music.ui.component.SwitchPreference
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.Updater
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.HomeViewModel
import com.metrolist.music.viewmodels.AccountSettingsViewModel

@Composable
fun AccountSettings(
    navController: NavController,
    onClose: () -> Unit,
    latestVersionName: String
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val (accountNamePref, onAccountNameChange) = rememberPreference(AccountNameKey, "")
    val (accountEmail, onAccountEmailChange) = rememberPreference(AccountEmailKey, "")
    val (accountChannelHandle, onAccountChannelHandleChange) = rememberPreference(AccountChannelHandleKey, "")
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (visitorData, onVisitorDataChange) = rememberPreference(VisitorDataKey, "")
    val (dataSyncId, onDataSyncIdChange) = rememberPreference(DataSyncIdKey, "")

    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

    val homeViewModel: HomeViewModel = hiltViewModel()
    val accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel()
    val accountName by homeViewModel.accountName.collectAsState()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsState()

    var showToken by remember { mutableStateOf(false) }
    var showTokenEditor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.close), contentDescription = null)
            }
        }

        Spacer(Modifier.height(12.dp))

        val accountSectionModifier = Modifier.clickable {
            onClose()
            if (isLoggedIn) {
                navController.navigate("account")
            } else {
                navController.navigate("login")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = accountSectionModifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            if (isLoggedIn && accountImageUrl != null) {
                AsyncImage(
                    model = accountImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.login),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isLoggedIn) accountName else stringResource(R.string.login),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 5.dp)
                )
            }

            if (isLoggedIn) {
                OutlinedButton(
                    onClick = {
                        accountSettingsViewModel.logoutAndClearSyncedContent(context, onInnerTubeCookieChange)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(stringResource(R.string.action_logout))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (showTokenEditor) {
            val text = """
                ***INNERTUBE COOKIE*** =$innerTubeCookie
                ***VISITOR DATA*** =$visitorData
                ***DATASYNC ID*** =$dataSyncId
                ***ACCOUNT NAME*** =$accountNamePref
                ***ACCOUNT EMAIL*** =$accountEmail
                ***ACCOUNT CHANNEL HANDLE*** =$accountChannelHandle
            """.trimIndent()

            TextFieldDialog(
                initialTextFieldValue = TextFieldValue(text),
                onDone = { data ->
                    data.split("\n").forEach {
                        when {
                            it.startsWith("***INNERTUBE COOKIE*** =") -> onInnerTubeCookieChange(it.substringAfter("="))
                            it.startsWith("***VISITOR DATA*** =") -> onVisitorDataChange(it.substringAfter("="))
                            it.startsWith("***DATASYNC ID*** =") -> onDataSyncIdChange(it.substringAfter("="))
                            it.startsWith("***ACCOUNT NAME*** =") -> onAccountNameChange(it.substringAfter("="))
                            it.startsWith("***ACCOUNT EMAIL*** =") -> onAccountEmailChange(it.substringAfter("="))
                            it.startsWith("***ACCOUNT CHANNEL HANDLE*** =") -> onAccountChannelHandleChange(it.substringAfter("="))
                        }
                    }
                },
                onDismiss = { showTokenEditor = false },
                singleLine = false,
                maxLines = 20,
                isInputValid = {
                    it.isNotEmpty() && "SAPISID" in parseCookieString(it)
                },
                extraContent = {
                    InfoLabel(text = stringResource(R.string.token_adv_login_description))
                }
            )
        }

        PreferenceEntry(
            title = {
                Text(
                    when {
                        !isLoggedIn -> stringResource(R.string.advanced_login)
                        showToken -> stringResource(R.string.token_shown)
                        else -> stringResource(R.string.token_hidden)
                    }
                )
            },
            icon = { Icon(painterResource(R.drawable.token), null) },
            onClick = {
                if (!isLoggedIn) showTokenEditor = true
                else if (!showToken) showToken = true
                else showTokenEditor = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
        )

        Spacer(Modifier.height(4.dp))

        if (isLoggedIn) {
            SwitchPreference(
                title = { Text(stringResource(R.string.more_content)) },
                description = null,
                icon = { Icon(painterResource(R.drawable.add_circle), null) },
                checked = useLoginForBrowse,
                onCheckedChange = {
                    YouTube.useLoginForBrowse = it
                    onUseLoginForBrowseChange(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            )
  
            Spacer(Modifier.height(4.dp))

            SwitchPreference(
                title = { Text(stringResource(R.string.yt_sync)) },
                icon = { Icon(painterResource(R.drawable.cached), null) },
                checked = ytmSync,
                onCheckedChange = onYtmSyncChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            )
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.integrations)) },
                icon = { Icon(painterResource(R.drawable.integration), null) },
                onClick = {
                    onClose()
                    navController.navigate("settings/integrations")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )

            Spacer(Modifier.height(4.dp))

            PreferenceEntry(
                title = { Text(stringResource(R.string.settings)) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (latestVersionName != BuildConfig.VERSION_NAME) {
                                Badge()
                            }
                        }
                    ) {
                        Icon(painterResource(R.drawable.settings), contentDescription = null)
                    }
                },
                onClick = {
                    onClose()
                    navController.navigate("settings")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )

            Spacer(Modifier.height(4.dp))

            if (latestVersionName != BuildConfig.VERSION_NAME) {
                val releaseInfo = Updater.getCachedLatestRelease()
                val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }
                
                if (downloadUrl != null) {
                    PreferenceEntry(
                        title = {
                            Text(text = stringResource(R.string.new_version_available))
                        },
                        description = latestVersionName,
                        icon = {
                            BadgedBox(badge = { Badge() }) {
                                Icon(painterResource(R.drawable.update), null)
                            }
                        },
                        onClick = {
                            uriHandler.openUri(downloadUrl)
                        }
                    )
                }
            }
        }
    }
}
