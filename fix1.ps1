$file = 'C:\Users\Admin\Downloads\yokai-amtl\app\src\main\java\eu\kanade\tachiyomi\ui\manga\MangaDetailsPresenter.kt'
$content = Get-Content $file -Raw

# Fix: Add UiPreferences import after GetTrack import
$old = 'import yokai.domain.track.interactor.GetTrack'
$new = 'import yokai.domain.track.interactor.GetTrack' + " 
\ + 'import yokai.domain.ui.UiPreferences'
$content = $content.Replace($old + \
import yokai.i18n.MR\, $new + \
import yokai.i18n.MR\)

# Fix: Add uiPreferences inject after insertTrack
$old = 'private val insertTrack: InsertTrack by injectLazy()'
$new = 'private val insertTrack: InsertTrack by injectLazy()' + \
\ + ' private val uiPreferences: UiPreferences by injectLazy()'
$content = $content.Replace($old + \

 private val allChapterScanlators\, $new + \

 private val allChapterScanlators\)

# Fix: Add showScanlatorBranches property after selectedScanlator
$old = 'var selectedScanlator: String? = null'
$new = 'var selectedScanlator: String? = null' + \

\ + ' val showScanlatorBranches: Boolean get() = uiPreferences.showMangaScanlatorBranches()'
$content = $content.Replace($old + \

 override val progressJobs\, $new + \

 override val progressJobs\)

Set-Content $file -Value $content -NoNewline
Write-Output 'Fix 1 applied'
