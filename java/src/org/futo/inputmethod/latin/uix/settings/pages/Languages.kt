package org.futo.inputmethod.latin.uix.settings.pages

import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.MultilingualBucketSetting
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.icon
import org.futo.inputmethod.latin.uix.kindTitle
import org.futo.inputmethod.latin.uix.namePreferenceKeyFor
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Route
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import org.futo.inputmethod.latin.uix.settings.Ios
import org.futo.inputmethod.latin.uix.settings.IosGroup
import org.futo.inputmethod.latin.uix.settings.IosIconTile
import org.futo.inputmethod.latin.uix.settings.IosSectionHeader
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.openModelImporter
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.latin.uix.theme.UixThemeWrapper
import org.futo.inputmethod.latin.uix.theme.presets.DynamicDarkTheme
import org.futo.inputmethod.latin.uix.urlEncode
import org.futo.inputmethod.latin.utils.Dictionaries
import org.futo.inputmethod.latin.utils.SubtypeLocaleUtils
import org.futo.inputmethod.latin.xlm.ModelPaths
import org.futo.inputmethod.updates.openURI
import java.util.Locale

private val InputMethodSubtype.layoutSetName
    get() = SubtypeLocaleUtils.getKeyboardLayoutSetName(this)

data class LanguageItem(
    val languageName: String,
    val options: LanguageOptions,
    val layouts: List<Pair<InputMethodSubtype, String>>,
    val inMultilingualBucket: Boolean
)

@Composable
fun LanguageConfigurable(
    kind: FileKind,
    selection: String,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
        .clickable(enabled = true) { onSelected() }
        .padding(start = 16.dp, top = 8.dp, end = 6.dp, bottom = 8.dp)
        .defaultMinSize(0.dp, 50.dp)) {
        Column(
            modifier = Modifier
                .weight(1.0f)
                .align(Alignment.CenterVertically)
        ) {
            Row {
                Icon(
                    painterResource(kind.icon()),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    kind.kindTitle(LocalResources.current),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = Typography.Small,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                selection,
                style = Typography.Body.RegularMl
            )

        }
        Icon(
            Icons.Default.ArrowForward,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .size(44.dp)
                .padding(10.dp)
        )
    }
}

@Composable
fun LayoutConfigurable(
    name: String,
    active: Boolean,
    onDelete: () -> Unit,
    canDelete: Boolean
) {
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 6.dp)
            .height(44.dp)
    ) {
        Text(
            name,
            style = Typography.Body.RegularMl,
            modifier = Modifier.align(Alignment.CenterVertically)
        )

        Spacer(modifier = Modifier.weight(1.0f))

        //Switch(checked = active, onCheckedChange = {}, modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(modifier = Modifier.width(8.dp))

        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterVertically)) {
                Icon(painterResource(id = R.drawable.trash), contentDescription = null)
            }
        }
    }
}

@Composable
fun ActionableItem(
    icon: Painter,
    text: String,
    color: Color,
    onTrigger: () -> Unit
) {
    TextButton(
        onClick = onTrigger, colors = ButtonColors(
            containerColor = color,
            contentColor = contentColorFor(color),
            disabledContainerColor = color,
            disabledContentColor = contentColorFor(color).copy(alpha = 0.75f)
        ), modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp, 0.dp)
            .height(44.dp)
    ) {
        Row {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text,
                modifier = Modifier.align(Alignment.CenterVertically),
                style = Typography.Body.Medium
            )
        }
    }
}

@Composable
fun LanguageSurface(
    item: LanguageItem,
    modifier: Modifier = Modifier,
    onConfigurableSelected: (FileKind) -> Unit,
    onLayoutRemoved: (InputMethodSubtype) -> Unit,
    onLayoutAdditionRequested: () -> Unit,
    onLanguageRemoved: () -> Unit,
    onToggleMultilingualBucket: (Boolean) -> Unit
) {
    // FMAboard: the language as iPhone-like groups instead of a card with big buttons
    val resources = LocalResources.current
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Column(modifier.fillMaxWidth()) {
        IosSectionHeader(item.languageName)
        IosGroup {
            listOf(
                Triple(FileKind.VoiceInput, item.options.voiceInputModel, Ios.Red),
                Triple(FileKind.Dictionary, item.options.dictionary, Ios.Orange),
                Triple(FileKind.Transformer, item.options.transformerModel, Ios.Indigo),
            ).forEach { (kind, selection, color) ->
                SettingItem(
                    title = kind.kindTitle(resources),
                    subtitle = selection ?: stringResource(R.string.language_settings_resource_none),
                    icon = { IosIconTile(painterResource(kind.icon()), color) },
                    onClick = { onConfigurableSelected(kind) }
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = faint,
                        modifier = Modifier.size(24.dp))
                }
            }
        }
        if (item.options.dictionary == null) {
            Tip(stringResource(R.string.language_settings_warning_language_has_no_dict))
        }

        IosSectionHeader(stringResource(R.string.language_settings_layouts_of_this_language))
        IosGroup {
            item.layouts.forEach {
                SettingItem(title = it.second, compact = true) {
                    if (item.layouts.size > 1) {
                        IconButton(onClick = { onLayoutRemoved(it.first) }) {
                            Icon(painterResource(id = R.drawable.trash), contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            SettingItem(
                title = stringResource(R.string.language_settings_add_layout_for_this_language),
                compact = true,
                onClick = onLayoutAdditionRequested,
                icon = { Icon(painterResource(id = R.drawable.plus_circle), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
            ) {}
        }

        IosGroup {
            SettingToggleRaw(
                title = stringResource(R.string.language_settings_enable_multilingual_typing_for_this_language),
                enabled = item.inMultilingualBucket,
                setValue = { onToggleMultilingualBucket(it) },
                compact = true
            )
        }

        IosGroup {
            SettingItem(
                title = stringResource(R.string.language_settings_remove_this_language),
                compact = true,
                onClick = onLanguageRemoved,
                icon = { Icon(painterResource(id = R.drawable.trash), contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)) }
            ) {}
        }
    }
}

@Preview
@Composable
fun LanguageSurfacePreview() {
    UixThemeWrapper(colorScheme = DynamicDarkTheme.obtainColors(LocalContext.current)) {
        LanguageSurface(
            item = LanguageItem(
                languageName = "Language Name",
                options = LanguageOptions(
                    "Model Name",
                    "Model Name",
                    "Model Name"
                ),
                layouts = listOf(
                    InputMethodSubtype.InputMethodSubtypeBuilder().build() to "QWERTY",
                    InputMethodSubtype.InputMethodSubtypeBuilder().build() to "Dvorak"
                ),
                inMultilingualBucket = true
            ),
            onLanguageRemoved = { }, onLayoutRemoved = { }, onConfigurableSelected = { },
            onLayoutAdditionRequested = { }, onToggleMultilingualBucket = { })
    }
}

data class LanguageOptions(
    val voiceInputModel: String?,
    val dictionary: String?,
    val transformerModel: String?
)

@Composable
fun ConfirmResourceActionDialog(
    onDismissRequest: () -> Unit,

    onExplore: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,

    resourceKind: FileKind,
    isCurrentlySet: Boolean,
    locale: Locale
) {
    val hasBuiltInFallback = if (resourceKind == FileKind.VoiceInput) {
        ResourceHelper.BuiltInVoiceInputFallbacks[locale.language] != null
    } else if (resourceKind == FileKind.Dictionary) {
        Dictionaries.getDictionaryIfExists(LocalContext.current, locale, Dictionaries.DictionaryKind.Any) != null
    } else {
        true
    }

    AlertDialog(
        icon = {
            Icon(painterResource(id = resourceKind.icon()), contentDescription = null)
        },
        title = {
            Text(text = "${locale.displayLanguage} - ${resourceKind.kindTitle(LocalResources.current)}")
        },
        text = {
            if (isCurrentlySet) {
                Text(
                    text = when (resourceKind) {
                        FileKind.VoiceInput -> stringResource(R.string.language_settings_resource_voice_input_selected)
                        FileKind.Transformer -> stringResource(R.string.language_settings_resource_transformer_selected)
                        FileKind.Dictionary -> stringResource(R.string.language_settings_resource_dictionary_selected)
                        FileKind.Invalid -> ""
                    } + if (!hasBuiltInFallback) {
                        "\n\n" +
                                when (resourceKind) {
                                    FileKind.VoiceInput -> stringResource(R.string.language_settings_resource_voice_input_selected_no_default_warning)
                                    FileKind.Transformer -> stringResource(R.string.language_settings_resource_transformer_selected_no_default_warning)
                                    FileKind.Dictionary -> stringResource(R.string.language_settings_resource_dictionary_selected_no_default_warning)
                                    FileKind.Invalid -> ""
                                }
                    } else {
                        ""
                    }
                )
            } else {
                Text(
                    text = when (resourceKind) {
                        FileKind.VoiceInput -> stringResource(R.string.language_settings_resource_voice_input_selected_unset)
                        FileKind.Transformer -> stringResource(R.string.language_settings_resource_transformer_selected_unset)
                        FileKind.Dictionary -> stringResource(R.string.language_settings_resource_dictionary_selected_unset)
                        FileKind.Invalid -> ""
                    }
                )
            }
        },
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            TextButton(onClick = {
                onImport()
            }) {
                Text(
                    if (isCurrentlySet) {
                        stringResource(R.string.language_settings_resource_replace_button)
                    } else {
                        stringResource(R.string.language_settings_resource_import_file_button)
                    }
                )
            }
        },
        dismissButton = {
            if (isCurrentlySet) {
                TextButton(onClick = { onDelete() }) {
                    if (hasBuiltInFallback) {
                        Text(stringResource(R.string.language_settings_resource_revert_to_default_button))
                    } else {
                        Text(stringResource(R.string.language_settings_resource_remove_button))
                    }

                }
            } else {
                TextButton(onClick = { onExplore() }) {
                    Text(stringResource(R.string.language_settings_resource_explore_online_button))
                }
            }
        }
    )
}


@Composable
fun ConfirmDeleteLanguageDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    locale: Locale
) {
    AlertDialog(
        icon = {
            Icon(painterResource(id = R.drawable.trash), contentDescription = null)
        },
        title = {
            Text(
                text = stringResource(
                    R.string.language_settings_remove_language_title,
                    locale.displayLanguage
                )
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.language_settings_remove_language_body,
                    locale.displayLanguage
                )
            )
        },
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.language_settings_remove_language_remove_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.language_settings_remove_language_cancel_button))
            }
        }
    )
}


data class DeleteInfo(
    val locale: Locale,
    val kind: FileKind
)

val LanguageSettingsTop = listOf(
    userSettingNavigationItem(
        title = R.string.language_settings_add_language_button,
        style = NavigationItemStyle.Misc,
        navigateTo = "addLanguage",
    )
)
val LanguageSettingsBottom = listOf(
    userSettingNavigationItem(
        title = R.string.language_settings_import_resource_from_file,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            openModelImporter(nav.context)
        },
    ),
    userSettingNavigationItem(
        title = R.string.language_settings_explore_voice_input_models_online,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            nav.context.openURI(
                FileKind.VoiceInput.getAddonUrlForLocale(null),
                true
            )
        },
    ),
    userSettingNavigationItem(
        title = R.string.language_settings_explore_dictionaries_online,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            nav.context.openURI(
                FileKind.Dictionary.getAddonUrlForLocale(null),
                true
            )
        },
    ),
    userSettingNavigationItem(
        title = R.string.language_settings_explore_transformers_online,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            nav.context.openURI(
                FileKind.Transformer.getAddonUrlForLocale(null),
                true
            )
        },
    )
)

val LanguageSettingsLite = UserSettingsMenu(
    title = R.string.language_settings_title,
    navPath = "languages", registerNavPath = false,
    settings = LanguageSettingsTop + listOf(
        userSettingNavigationItem(
            title = R.string.language_settings_manage_languages_title,
            subtitle = R.string.language_settings_manage_languages_subtitle,
            navigateTo = "languages",
            style = NavigationItemStyle.Misc
        )
    ) + LanguageSettingsBottom
)

@Preview(showBackground = true)
@Composable
fun LanguagesScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val deleteDialogInfo: MutableState<DeleteInfo?> = remember { mutableStateOf(null) }
    val languageDeleteInfo: MutableState<Locale?> = remember { mutableStateOf(null) }

    val inputMethods = useDataStoreValue(SubtypesSetting)
    val inputMethodList = remember(inputMethods) {
        Subtypes.layoutsMappedByLanguage(inputMethods)
    }

    val multilingualBucket = useDataStore(MultilingualBucketSetting)

    val inputMethodKeys = remember(inputMethodList) { inputMethodList.keys.toList().sorted() }

    if (deleteDialogInfo.value != null) {
        val info = deleteDialogInfo.value!!
        ConfirmResourceActionDialog(
            onDismissRequest = { deleteDialogInfo.value = null },
            onExplore = {
                context.openURI(info.kind.getAddonUrlForLocale(info.locale), true)
                deleteDialogInfo.value = null
            },
            onDelete = {
                ResourceHelper.deleteResourceForLanguage(context, info.kind, info.locale)
                deleteDialogInfo.value = null
            },
            onImport = {
                openModelImporter(context)
                deleteDialogInfo.value = null
            },

            resourceKind = info.kind,
            locale = info.locale,
            isCurrentlySet = runBlocking {
                ResourceHelper.findFileForKind(
                    context,
                    info.locale,
                    info.kind
                )?.exists() == true
            }
        )
    } else if (languageDeleteInfo.value != null) {
        val info = languageDeleteInfo.value!!
        ConfirmDeleteLanguageDialog(
            locale = info,
            onDelete = {
                languageDeleteInfo.value = null

                val key = inputMethodKeys.find { localeString ->
                    Subtypes.getLocale(localeString) == info
                }

                if (key != null) {
                    val subtypes = inputMethodList[key]!!
                    subtypes.forEach { Subtypes.removeLanguage(context, it) }

                    if(multilingualBucket.value.contains(key)) {
                        multilingualBucket.value.toMutableSet().apply {
                            remove(key)
                            multilingualBucket.setValue(this)
                        }
                    }
                }
            },
            onDismissRequest = {
                languageDeleteInfo.value = null
            }
        )
    }
    LazyColumn {
        item {
            ScreenTitle(
                stringResource(R.string.language_settings_title),
                showBack = true,
                navController
            )
        }

        item { IosGroup { LanguageSettingsTop.forEach { it.component() } } } // FMAboard: grouped rows

        items(inputMethodKeys) { localeString ->
            val subtypes = inputMethodList[localeString]!!

            val locale = Subtypes.getLocale(localeString)

            val name = Subtypes.getName(subtypes.first())

            val voiceInputModelName = ResourceHelper.tryFindingVoiceInputModelForLocale(
                context,
                locale
            )?.name?.let { stringResource(it) }
            val dictionaryName = runBlocking {
                ResourceHelper.findKeyForLocaleAndKind(
                    context,
                    locale,
                    FileKind.Dictionary
                )
            }?.let {
                runBlocking {
                    context.getSetting(
                        FileKind.Dictionary.namePreferenceKeyFor(it),
                        "Dictionary"
                    )
                } + " " + resources.getString(
                    R.string.language_settings_resource_imported_indicator
                )
             } ?: if (Dictionaries.getDictionaryIfExists(context, locale, Dictionaries.DictionaryKind.Any) != null) {
                resources.getString(R.string.language_settings_resource_builtin_dictionary_name)
            } else {
                null
            }

            val transformerName =
                runBlocking { ModelPaths.getModelOptions(context) }.get(locale.language)?.let {
                    it.loadDetails()?.let {
                        it.name + if (it.isUnsupported()) (" " + resources.getString(R.string.language_settings_resource_unsupported_indicator)) else ""
                    }
                }

            val options = LanguageOptions(
                voiceInputModel = voiceInputModelName,
                dictionary = dictionaryName,
                transformerModel = transformerName
            )


            LanguageSurface(
                LanguageItem(
                    languageName = name,
                    options = options,
                    layouts = subtypes.map {
                        val name = Subtypes.getLayoutName(context, it.layoutSetName)
                        it to name
                    },
                    inMultilingualBucket = multilingualBucket.value.contains(
                        localeString
                    )
                ),
                onLanguageRemoved = {
                    languageDeleteInfo.value = locale
                },
                onLayoutRemoved = { subtype ->
                    val layoutSetName = subtype.layoutSetName
                    if(layoutSetName.startsWith("custom")) {
                        val i = layoutSetName.substring("custom".length).toIntOrNull()
                        if(i != null) {
                            navController.navigate(Route.DevLayoutEdit(i))
                        }
                    } else {
                        Subtypes.removeLanguage(context, subtype)
                    }
                },
                onConfigurableSelected = { kind ->
                    if (kind == FileKind.Transformer && transformerName != null) {
                        navController.navigate("models")
                    } else {
                        deleteDialogInfo.value = DeleteInfo(locale, kind)
                    }
                },
                onLayoutAdditionRequested = {
                    navController.navigate(Route.AddLayout(locale.toLanguageTag()))
                },
                onToggleMultilingualBucket = { to ->
                    val newSet = multilingualBucket.value.toMutableSet()
                    if (to) {
                        newSet.add(localeString)
                    } else {
                        newSet.remove(localeString)
                    }

                    multilingualBucket.setValue(newSet)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            ScreenTitle(stringResource(R.string.language_settings_other_options))
        }
        item { IosGroup { LanguageSettingsBottom.forEach { it.component() } } }
    }
}