package io.github.maxlyth.hapaneld

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.HaTransportFault
import java.util.Locale

/** Keep native Android resources aligned with ha-paneld's locale-selection policy. */
internal object NativeLocale {
    @Volatile private var resourceLanguageTag: String? = null

    /** Read only the downgrade-compatible XML mirror; Guard DB recovery must not construct Config/AppState. */
    fun applyBeforeDatabase(context: Context) {
        val raw = runCatching {
            context.applicationContext
                .getSharedPreferences(LEGACY_CONFIG_PREFERENCES, Context.MODE_PRIVATE)
                .getString(UI_LANGUAGE_KEY, AUTO_LANGUAGE)
        }.getOrNull()
        apply(raw ?: AUTO_LANGUAGE)
    }

    fun apply(raw: String) {
        val automatic = raw.equals(AUTO_LANGUAGE, ignoreCase = true)
        val locale = if (automatic) {
            AppLocale.automaticLocaleOverride(systemLanguageTag(), AppLocale.RELEASE_LOCALES)
        } else {
            AppLocale.canonical(raw, allowPseudo = BuildConfig.DEBUG)
        }
        resourceLanguageTag = locale
        val desired = locale?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    private fun systemLanguageTag(): String? =
        ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]?.toLanguageTag()

    fun string(context: Context, @StringRes id: Int, vararg formatArgs: Any): String {
        val tag = resourceLanguageTag
        val localized = if (Build.VERSION.SDK_INT >= 33 || tag == null) {
            context
        } else {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            context.createConfigurationContext(configuration)
        }
        return if (formatArgs.isEmpty()) localized.getString(id) else localized.getString(id, *formatArgs)
    }

    private const val LEGACY_CONFIG_PREFERENCES = "ha-paneld"
    private const val UI_LANGUAGE_KEY = "ui_language"
    private const val AUTO_LANGUAGE = "auto"
}

/**
 * Read `dark_mode` from the downgrade-compatible XML mirror, without constructing Config/AppState.
 *
 * Same contract, and same reason, as [NativeLocale.applyBeforeDatabase]: this runs in
 * `Application.onCreate`, inside the `startForegroundService` deadline on a cold start, where opening
 * the protected database costs whole seconds on slow panel hardware. `DowngradeCompatibleStatePersistence`
 * keeps the mirror in step on every commit, and `PaneldService.onCreate` re-asserts the authoritative
 * database value once it has already promoted to the foreground.
 *
 * The default matches the `dark_mode` SettingSpec default (`true`), so a panel whose mirror predates
 * the key themes exactly as the registry says it should.
 */
internal fun darkModeBeforeDatabase(context: Context): Boolean = runCatching {
    context.applicationContext
        .getSharedPreferences(LEGACY_CONFIG_MIRROR, Context.MODE_PRIVATE)
        .getBoolean(DARK_MODE_KEY, DARK_MODE_DEFAULT)
}.getOrDefault(DARK_MODE_DEFAULT)

private const val LEGACY_CONFIG_MIRROR = "ha-paneld"
private const val DARK_MODE_KEY = "dark_mode"
private const val DARK_MODE_DEFAULT = true

/** Services do not inherit AppCompat's activity locale override before Android 13. */
internal fun Context.nativeString(@StringRes id: Int, vararg formatArgs: Any): String =
    NativeLocale.string(this, id, *formatArgs)

internal fun Context.localizedLabel(operation: SensitiveOperation): String = getString(
    when (operation) {
        SensitiveOperation.APK_INSTALL -> R.string.approval_op_apk_install
        SensitiveOperation.APK_FETCH -> R.string.approval_op_apk_fetch
        SensitiveOperation.BACKUP_EXPORT -> R.string.approval_op_backup_export
        SensitiveOperation.CONFIG_SECRET_EXPORT -> R.string.approval_op_secret_export
        SensitiveOperation.CONFIG_IMPORT -> R.string.approval_op_config_import
        SensitiveOperation.BACKUP_RESTORE -> R.string.approval_op_backup_restore
        SensitiveOperation.PACKAGE_UNINSTALL -> R.string.approval_op_package_uninstall
        SensitiveOperation.PROFILE_ACTIVATE -> R.string.approval_op_profile_activate
        SensitiveOperation.DEVTOOLS_ENABLE -> R.string.approval_op_devtools_enable
        SensitiveOperation.REMOTE_MEDIA -> R.string.approval_op_remote_media
        SensitiveOperation.PACKAGE_TAME -> R.string.approval_op_package_tame
        SensitiveOperation.DASHBOARD_RELOAD -> R.string.approval_op_dashboard_reload
        SensitiveOperation.DEVICE_REBOOT -> R.string.approval_op_device_reboot
        SensitiveOperation.COMPANION_REPAIR -> R.string.approval_op_companion_repair
        SensitiveOperation.DASHBOARD_STORAGE_CLEAR -> R.string.approval_op_dashboard_storage_clear
        SensitiveOperation.DISPLAY_CONFIGURATION -> R.string.approval_op_display_configuration
        SensitiveOperation.POWER_CONFIGURATION -> R.string.approval_op_power_configuration
        SensitiveOperation.POWER_SAFETY_ACKNOWLEDGEMENT -> R.string.approval_op_power_safety_ack
        SensitiveOperation.GUARD_DB_MAINTENANCE -> R.string.approval_op_guard_db
        SensitiveOperation.CAMERA_ENABLE -> R.string.approval_op_camera_enable
        SensitiveOperation.TRANSPORT_RELEASE -> R.string.approval_op_transport_release
    },
)

/** A closed transport classification becomes user guidance; opaque platform text remains evidence. */
internal fun Context.localizedHaTransportFault(fault: HaTransportFault): String =
    getString(haTransportFaultResource(fault))

@StringRes
internal fun haTransportFaultResource(fault: HaTransportFault): Int =
    when (fault) {
        HaTransportFault.NONE -> R.string.ha_transport_not_ready
        HaTransportFault.TLS_TRUST -> R.string.ha_transport_tls_trust
        HaTransportFault.TLS_OTHER -> R.string.ha_transport_tls_other
        HaTransportFault.DNS -> R.string.ha_transport_dns
        HaTransportFault.TIMEOUT -> R.string.ha_transport_timeout
        HaTransportFault.REFUSED -> R.string.ha_transport_refused
        HaTransportFault.UNREACHABLE -> R.string.ha_transport_unreachable
        HaTransportFault.HTTP_STATUS -> R.string.ha_transport_http_status
        HaTransportFault.PROTOCOL -> R.string.ha_transport_protocol
        HaTransportFault.UNKNOWN -> R.string.ha_transport_unknown
    }
