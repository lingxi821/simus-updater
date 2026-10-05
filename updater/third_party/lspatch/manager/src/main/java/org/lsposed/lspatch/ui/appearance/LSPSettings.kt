package org.lsposed.lspatch.ui.appearance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.lspatch.BuildConfig
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ui.ambience.AmbienceSettings
import org.matrix.vector.ui.appearance.AppearanceSettings
import org.matrix.vector.ui.locale.LocaleController
import org.matrix.vector.ui.navigation.FloatingNavSettings
import org.matrix.vector.ui.navigation.NavPanelStore
import org.matrix.vector.ui.net.NetworkSettings

/** LSPatch's brand seed (warm amber), the default accent when dynamic colour is off. */
const val LSPATCH_SEED: Int = 0xFFE08A3C.toInt()

/**
 * LSPatch's appearance + language preferences, persisted in the app's `settings` store.
 *
 * The shared appearance controls and the status header are written against plain values and callbacks, so this is the
 * whole of LSPatch's binding to them: each preference is a [StateFlow] the UI collects, and a setter that writes the
 * pref and pushes the new value. Mirrors what Vector keeps in its SettingsRepository, minus everything LSPatch has no
 * use for.
 */
object LSPSettings : AppearanceSettings, LocaleController, NetworkSettings {
    private val prefs
        get() = lspApp.prefs

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME_MODE, "system") ?: "system")
    override val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    override fun setThemeMode(value: String) {
        prefs.edit().putString(KEY_THEME_MODE, value).apply()
        _themeMode.value = value
    }

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
    override val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    override fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        _dynamicColor.value = value
    }

    private val _seedColor = MutableStateFlow(prefs.getInt(KEY_SEED_COLOR, LSPATCH_SEED))
    override val seedColor: StateFlow<Int> = _seedColor.asStateFlow()

    override fun setSeedColor(value: Int) {
        prefs.edit().putInt(KEY_SEED_COLOR, value).apply()
        _seedColor.value = value
    }

    private val _amoledBlack = MutableStateFlow(prefs.getBoolean(KEY_AMOLED, false))
    override val amoledBlack: StateFlow<Boolean> = _amoledBlack.asStateFlow()

    override fun setAmoledBlack(value: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED, value).apply()
        _amoledBlack.value = value
    }

    private val _headerAmbience = MutableStateFlow(prefs.getString(KEY_AMBIENCE, "maze") ?: "maze")
    override val headerAmbience: StateFlow<String> = _headerAmbience.asStateFlow()

    override fun setHeaderAmbience(key: String) {
        prefs.edit().putString(KEY_AMBIENCE, key).apply()
        _headerAmbience.value = key
    }

    private val _floatingNav = MutableStateFlow(prefs.getBoolean(KEY_FLOATING_NAV, false))
    /** Whether the navigation is a floating ball over the content instead of a bar/rail. */
    val floatingNav: StateFlow<Boolean> = _floatingNav.asStateFlow()

    fun setFloatingNav(value: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_NAV, value).apply()
        _floatingNav.value = value
    }

    private val _manageTab = MutableStateFlow(prefs.getInt(KEY_MANAGE_TAB, 0))

    /**
     * Which tab the Manage panel opens on: 0 Applications, 1 Modules.
     *
     * Kept here rather than passed to the panel, because a panel is identified by its route: one carrying "open the
     * modules tab this time" would be a different panel on every visit, and the bar highlighting it, the ball, and the
     * check for "already there" would each stop matching. Remembering it also means the panel comes back on the tab it
     * was left on.
     */
    val manageTab: StateFlow<Int> = _manageTab.asStateFlow()

    fun setManageTab(index: Int) {
        prefs.edit().putInt(KEY_MANAGE_TAB, index).apply()
        _manageTab.value = index
    }

    private val _dohEnabled = MutableStateFlow(prefs.getBoolean(KEY_DOH_ENABLED, true))
    override val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

    override fun setDohEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOH_ENABLED, enabled).apply()
        _dohEnabled.value = enabled
    }

    private val _navPanels = MutableStateFlow(storedNavPanels())

    /**
     * The arrangement, from this store or from the one it used to live in.
     *
     * The panels were persisted by the shell in a preferences file of its own before they had a home here. Reading that
     * file once, when this one has nothing to say, is what keeps someone who has arranged their panels from finding
     * them back in declaration order after an update.
     */
    private fun storedNavPanels(): String {
        prefs
            .getString(KEY_NAV_PANELS, "")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        return lspApp
            .getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
            .getString(KEY_NAV_PANELS, "") ?: ""
    }

    /** The navigation panels' order and hidden set, encoded -- see NavPanels for the format. */
    val navPanels: StateFlow<String> = _navPanels.asStateFlow()

    fun setNavPanels(encoded: String) {
        prefs.edit().putString(KEY_NAV_PANELS, encoded).apply()
        _navPanels.value = encoded
    }

    private val _appLocale = MutableStateFlow(prefs.getString(KEY_LOCALE, "") ?: "")
    override val appLocale: StateFlow<String> = _appLocale.asStateFlow()

    override fun setAppLocale(tag: String) {
        prefs.edit().putString(KEY_LOCALE, tag).apply()
        _appLocale.value = tag
    }

    // Listed at build time from the resource folders that carry our own strings.xml, so a language
    // appears the moment a translator's folder lands and no list has to be kept in step by hand --
    // the picker must not offer a language there is no translation for.
    override val availableTags: List<String> = BuildConfig.TRANSLATIONS.split(',').filter { it.isNotBlank() }

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_SEED_COLOR = "seed_color"
    private const val KEY_AMOLED = "amoled_black"
    private const val KEY_AMBIENCE = "header_ambience"
    private const val KEY_LOCALE = "app_locale"
    private const val KEY_FLOATING_NAV = "floating_nav"
    private const val KEY_DOH_ENABLED = "doh_enabled"
    private const val KEY_MANAGE_TAB = "manage_tab"
    private const val KEY_NAV_PANELS = "nav_panels"
}

/**
 * The panel arrangement, as the shared navigator's port.
 *
 * Kept in the same store as every other preference. It used to live in a second preferences file read straight from the
 * activity, which is one store too many for one app's settings and left the arrangement out of reach of anything that
 * was not the shell.
 */
object LSPNavPanelStore : NavPanelStore {

    override val encoded: StateFlow<String>
        get() = LSPSettings.navPanels

    override fun setEncoded(value: String) {
        LSPSettings.setNavPanels(value)
    }
}

/**
 * Where the floating nav ball rests, persisted so a rotation or relaunch puts it back — the LSPatch side of the shared
 * [FloatingNavSettings], mirroring how [LSPAmbienceSettings] persists the header.
 */
object LSPFloatingNavSettings : FloatingNavSettings {
    private val prefs
        get() = lspApp.prefs

    override fun atEnd(): Boolean = prefs.getBoolean("floating_nav_at_end", true)

    override fun y(): Float = prefs.getFloat("floating_nav_y", 0.72f)

    override fun setAtEnd(atEnd: Boolean) {
        prefs.edit().putBoolean("floating_nav_at_end", atEnd).apply()
    }

    override fun setY(fraction: Float) {
        prefs.edit().putFloat("floating_nav_y", fraction).apply()
    }
}

/**
 * Persists the header ambient's scale / speed / variant, so a pinch or a double-tap on it survives a relaunch — the
 * LSPatch equivalent of Vector's VectorAmbienceSettings.
 */
object LSPAmbienceSettings : AmbienceSettings {
    private val prefs
        get() = lspApp.prefs

    override fun scale(key: String): Float = prefs.getFloat("ambience_scale_$key", 1f)

    override fun speed(key: String): Float = prefs.getFloat("ambience_speed_$key", 1f)

    override fun variant(key: String): Int = prefs.getInt("ambience_variant_$key", 0)

    override fun setScale(key: String, value: Float) {
        prefs.edit().putFloat("ambience_scale_$key", value).apply()
    }

    override fun setSpeed(key: String, value: Float) {
        prefs.edit().putFloat("ambience_speed_$key", value).apply()
    }

    override fun setVariant(key: String, value: Int) {
        prefs.edit().putInt("ambience_variant_$key", value).apply()
    }
}
