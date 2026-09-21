/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.NotificationStyle

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  // Default MPV config storage location to /storage/emulated/0/mpv on first run.
  // SAF tree URI form for the primary-volume "mpv" folder. Until the user actually grants the
  // picker permission, openPersistedTreeDocument() returns null and the app falls back to the
  // in-app preferences (no crash). Picking the folder via the setting overwrites this with the
  // real, granted URI. The script directory is derived as <this>/scripts, i.e.
  // /storage/emulated/0/mpv/scripts.
  val mpvConfStorageUri =
    preferenceStore.getString(
      "mpv_conf_storage_location_uri",
      "content://com.android.externalstorage.documents/tree/primary:mpv",
    )
  val mpvConf = preferenceStore.getString("mpv.conf")
  val inputConf = preferenceStore.getString("input.conf")
  val mpvConfOverrides = preferenceStore.getStringSet("mpv_conf_overrides", emptySet())

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")

  val enabledStatisticsPage = preferenceStore.getInt("enabled_stats_page", 0)

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

  val enableLuaScripts = preferenceStore.getBoolean("enable_lua_scripts", false)
  val selectedLuaScripts = preferenceStore.getStringSet("selected_lua_scripts", emptySet())

  fun userScriptsConfigurationKey(): String {
    if (!enableLuaScripts.get()) return "disabled"
    return buildString {
      val location = mpvConfStorageUri.get()
      append(location.length).append(':').append(location)
      selectedLuaScripts.get().sorted().forEach { script ->
        append('|').append(script.length).append(':').append(script)
      }
    }
  }

  val enableP2pStreaming = preferenceStore.getBoolean("enable_p2p_streaming", true)

  val enableHlsProxy = preferenceStore.getBoolean("enable_hls_proxy", true)

  /** Notification style for the playback service (Media vs Progress-centric on Android 16+). */
  val notificationStyle = preferenceStore.getEnum("notification_style", NotificationStyle.Media)
}
