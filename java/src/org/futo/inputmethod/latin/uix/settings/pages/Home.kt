package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.ATLCompat
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.fmaboard.AiHomeEntry
import org.futo.inputmethod.latin.fmaboard.GuideMenu
import org.futo.inputmethod.latin.fmaboard.TypingLogMenu
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.SYSTEM_VOICE_INPUT_PACKAGE
import org.futo.inputmethod.latin.uix.TextEditPopupActivity
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.settings.Ios
import org.futo.inputmethod.latin.uix.settings.IosIconTile
import org.futo.inputmethod.latin.uix.settings.IosSectionHeader
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.render
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleSharedPrefs
import org.futo.inputmethod.updates.openManualUpdateCheck

// FMAboard: switches people change most often, right on the home screen (the same settings as inside the menus)
private fun quickToggle(
    @StringRes title: Int, key: String, @DrawableRes icon: Int, color: Color, default: @Composable () -> Boolean
) = userSettingToggleSharedPrefs(
    title = title, key = key, default = default,
    icon = { IosIconTile(painterResource(icon), color) }
).copy(appearsInSearch = false)

private fun header(text: String) = userSettingDecorationOnly { IosSectionHeader(text) }

private fun nav(
    @StringRes title: Int, to: String, @DrawableRes icon: Int, color: Color, @StringRes subtitle: Int? = null
) = userSettingNavigationItem(
    title = title, subtitle = subtitle, style = NavigationItemStyle.Misc,
    navigateTo = to, icon = icon, iconColor = color
)

val HomeScreenLite = UserSettingsMenu(
    title = R.string.settings_home_title,
    navPath = "home", registerNavPath = false,
    settings = listOf(
        userSettingDecorationOnly {
            val context = LocalContext.current
            if(ATLCompat.IsATL) {
                NavigationItem(
                    title = "Launch keyboard (window)",
                    style = NavigationItemStyle.Misc,
                    navigate = { ATLCompat.launchKeyboard(context, false) }
                )
                NavigationItem(
                    title = "Launch keyboard (layershell)",
                    style = NavigationItemStyle.Misc,
                    navigate = { ATLCompat.launchKeyboard(context, true) }
                )
            }

        },

        header("Lo más usado"),
        quickToggle(R.string.auto_correction, Settings.PREF_AUTO_CORRECTION, R.drawable.icon_spellcheck, Ios.Blue) { true },
        quickToggle(R.string.auto_cap, Settings.PREF_AUTO_CAP, R.drawable.fmaboard_caps, Ios.Indigo) { true },
        quickToggle(R.string.swipe_settings_enable_swipe_typing, Settings.PREF_GESTURE_INPUT, R.drawable.swipe_icon, Ios.Orange) { true },
        quickToggle(R.string.keyboard_settings_show_number_row, Settings.PREF_ENABLE_NUMBER_ROW, R.drawable.hash, Ios.Teal) { false },
        quickToggle(R.string.vibrate_on_keypress, Settings.PREF_VIBRATE_ON, R.drawable.fmaboard_vibrate, Ios.Pink) {
            booleanResource(R.bool.config_default_vibration_enabled)
        },
        quickToggle(R.string.sound_on_keypress, Settings.PREF_SOUND_ON, R.drawable.fmaboard_volume, Ios.Red) {
            booleanResource(R.bool.config_default_sound_enabled)
        },

        header("Teclado"),
        nav(R.string.language_settings_title, "languages", R.drawable.globe, Ios.Blue),
        nav(R.string.settings_keyboard_typing_title, "keyboardAndTyping", R.drawable.keyboard, Ios.Gray),
        nav(R.string.theme_settings_title, "themes", R.drawable.themes, Ios.Pink),
        nav(R.string.prediction_settings_title, PredictiveTextMenu.navPath, R.drawable.text_prediction, Ios.Indigo),
        nav(SwipeMenu.title, SwipeMenu.navPath, R.drawable.swipe_icon, Ios.Orange),
        UserSetting(
            name = R.string.voice_input_settings_title
        ) {
            val navController = LocalNavController.current
            val useExternal = useDataStoreValue(USE_SYSTEM_VOICE_INPUT)
            val externalPkg = useDataStoreValue(SYSTEM_VOICE_INPUT_PACKAGE)
            val name = usePackageReadableName(externalPkg)

            NavigationItem(
                title = stringResource(R.string.voice_input_settings_title),
                style = NavigationItemStyle.Misc,
                subtitle = if(useExternal && name != null) {
                    stringResource(R.string.voice_input_settings_builtin_disabled_notice2, name)
                } else { null },
                navigate = { navController!!.navigate(VoiceInputMenu.navPath) },
                icon = painterResource(R.drawable.mic_fill),
                iconColor = Ios.Red
            )
        },

        header("FMAboard"),
        AiHomeEntry,
        nav(R.string.fmaboard_title, TypingLogMenu.navPath, R.drawable.eye, Ios.Blue),
        nav(R.string.fmaboard_guide_title, GuideMenu.navPath, R.drawable.book, Ios.Orange),
        nav(R.string.action_settings_title, "actions", R.drawable.smile, Ios.Yellow),

        header(""),
        userSettingNavigationItem(
            title = R.string.payment_screen_short_title,
            style = NavigationItemStyle.Misc,
            navigateTo = "payment",
            icon = R.drawable.dollar_sign,
            iconColor = Ios.Green,
        ).copy(visibilityCheck = {
            useDataStoreValue(IS_ALREADY_PAID) == false
        }, appearInSearchIfVisibilityCheckFailed = false),
        nav(R.string.help_menu_title, "help", R.drawable.help_circle, Ios.Blue),
        userSettingNavigationItem(
            title = R.string.fmaboard_check_updates,
            style = NavigationItemStyle.Misc,
            navigate = { nav -> nav.context.openManualUpdateCheck() },
            icon = R.drawable.fmaboard_refresh,
            iconColor = Ios.Gray
        ),

        header(""),
        userSettingNavigationItem(
            title = R.string.dev_settings_title,
            style = NavigationItemStyle.Misc,
            navigateTo = "developer",
            icon = R.drawable.code,
            iconColor = Ios.Gray
        ).copy(visibilityCheck = {
            useDataStoreValue(IS_DEVELOPER) == true || LocalInspectionMode.current
        }),
        nav(R.string.misc_settings_title, "misc", R.drawable.settings, Ios.Gray),
        nav(R.string.credits_menu_title, "credits", R.drawable.file_text, Ios.Gray),
    )
)

/** Search field look-alike: tapping it opens the settings search. */
@Composable
private fun HomeSearchField(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Buscar un ajuste", style = Ios.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val isPaid = useDataStoreValue(IS_ALREADY_PAID)

    Column {
        Column(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(36.dp))
            Text(
                "Ajustes", style = Ios.LargeTitle,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            )
            HomeSearchField { navController.navigate("search") }

            // FMAboard: FUTO's download-site notice left out; updates are on GitHub
            ConditionalUnpaidNoticeWithNav(navController)

            HomeScreenLite.render(showTitle = false)

            Spacer(modifier = Modifier.height(16.dp))

            if(isPaid || LocalInspectionMode.current) {
                Text(
                    stringResource(R.string.payment_paid_version_indicator),
                    style = Ios.Footnote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                "FMAboard ${BuildConfig.VERSION_NAME.removeSuffix("-unstable")}",
                style = Ios.Footnote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        Text(
            stringResource(R.string.settings_try_typing_here),
            style = Ios.RowTitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable {
                    val intent = Intent()
                    intent.setClass(context, TextEditPopupActivity::class.java)
                    intent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    context.startActivity(intent)
                }
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}
