package yokai.domain.backup

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.preference.PreferenceKeys

class BackupPreferences(private val preferenceStore: PreferenceStore) {

    fun numberOfBackups() = preferenceStore.getInt(PreferenceKeys.numberOfBackups, 5)

    fun backupInterval() = preferenceStore.getInt(PreferenceKeys.backupInterval, 0)

    fun lastAutoBackupTimestamp() = preferenceStore.getLong(Preference.appStateKey("last_auto_backup_timestamp"), 0L)

    fun cloudBackupUri() = preferenceStore.getString(
        key = Preference.appStateKey("cloud_backup_uri"),
        defaultValue = "",
    )

    fun autoBackupToCloud() = preferenceStore.getBoolean(Preference.appStateKey("auto_backup_to_cloud"), true)
}
