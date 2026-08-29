package com.metrolist.music.ui.screens.equalizer.wizard

import androidx.compose.animation.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R

/**
 * EQ Wizard - Device Setup Flow
 * Two steps: Model → Variants
 */
@Composable
fun WizardScreen(
    viewModel: WizardViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) {
            onNavigateBack()
        }
    }

    WizardScreenContent(
        state = state,
        onDownloadDatabase = { viewModel.downloadDatabase() },
        onModelSearchQueryChanged = { viewModel.onModelSearchQueryChanged(it) },
        onModelSelected = { viewModel.onModelSelected(it) },
        onVariantToggled = { viewModel.onVariantToggled(it) },
        onNextClicked = { viewModel.onNextClicked() },
        onBackClicked = { viewModel.onBackClicked() },
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
private fun WizardScreenContent(
    state: WizardState,
    onDownloadDatabase: () -> Unit,
    onModelSearchQueryChanged: (String) -> Unit,
    onModelSelected: (DeviceModel) -> Unit,
    onVariantToggled: (String) -> Unit,
    onNextClicked: () -> Unit,
    onBackClicked: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.currentStep) {
                            WizardStep.MODEL_SELECTION -> stringResource(R.string.wizard_select_model)
                            WizardStep.VARIANT_SELECTION -> stringResource(R.string.wizard_select_profiles)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (state.canGoBack) onBackClicked else onNavigateBack) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                HorizontalDivider()

                AnimatedContent(
                    targetState = state.currentStep,
                    label = "wizard_step",
                    transitionSpec = {
                        (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
                    }
                ) { step ->
                    when (step) {
                        WizardStep.MODEL_SELECTION -> ModelSelectionStep(
                            searchQuery = state.modelSearchQuery,
                            models = state.models,
                            isLoading = state.isLoading,
                            isDatabaseReady = state.isDatabaseReady,
                            onDownloadDatabase = onDownloadDatabase,
                            onSearchQueryChanged = onModelSearchQueryChanged,
                            onModelSelected = onModelSelected
                        )
                        WizardStep.VARIANT_SELECTION -> VariantSelectionStep(
                            modelName = state.selectedModel?.name ?: "",
                            variants = state.variants,
                            selectedVariantIds = state.selectedVariantIds,
                            isLoading = state.isLoading,
                            onVariantToggled = onVariantToggled,
                            onCompleteClicked = onNextClicked,
                            canComplete = state.canProceed
                        )
                    }
                }
            }

            if (state.error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text(state.error)
                }
            }
        }
    }
}

// STEP 1: MODEL SELECTION

@Composable
private fun ModelSelectionStep(
    searchQuery: String,
    models: List<DeviceModel>,
    isLoading: Boolean,
    isDatabaseReady: Boolean,
    onDownloadDatabase: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onModelSelected: (DeviceModel) -> Unit
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    if (!isDatabaseReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.eq_downloading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Button(onClick = onDownloadDatabase) {
                    Icon(
                        painter = painterResource(R.drawable.download),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.eq_download_db))
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.wizard_search_model_hint),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            label = { Text(stringResource(R.string.wizard_model_name)) },
            placeholder = { Text(stringResource(R.string.wizard_model_placeholder)) },
            leadingIcon = {
                Icon(painterResource(R.drawable.search), contentDescription = null)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (models.isEmpty() && searchQuery.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.wizard_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(models) { model ->
                    ModelItem(
                        model = model,
                        onClick = { onModelSelected(model) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: DeviceModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.graphic_eq),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium
                )
                if (model.hasMultipleVariants) {
                    Text(
                        text = stringResource(R.string.wizard_multiple_variants),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null
            )
        }
    }
}

// STEP 2: VARIANT SELECTION

@Composable
private fun VariantSelectionStep(
    modelName: String,
    variants: List<EQProfileVariant>,
    selectedVariantIds: Set<String>,
    isLoading: Boolean,
    onVariantToggled: (String) -> Unit,
    onCompleteClicked: () -> Unit,
    canComplete: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.wizard_select_eq_profiles, modelName),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(variants) { variant ->
                VariantItem(
                    variant = variant,
                    isSelected = selectedVariantIds.contains(variant.id),
                    onToggle = { onVariantToggled(variant.id) }
                )
            }
        }

        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
            Button(
                onClick = onCompleteClicked,
                enabled = canComplete && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomPadding)
                    .height(56.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.wizard_save_profiles),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantItem(
    variant: EQProfileVariant,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = variant.displayName.substringBefore(" - "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                if (variant.sourceDisplay.isNotEmpty() || variant.rigDisplay.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (variant.sourceDisplay.isNotEmpty()) {
                        Text(
                            text = variant.sourceDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (variant.rigDisplay.isNotEmpty()) {
                        Text(
                            text = variant.rigDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
