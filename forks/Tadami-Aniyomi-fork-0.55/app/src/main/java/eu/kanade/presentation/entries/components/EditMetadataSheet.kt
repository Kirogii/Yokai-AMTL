package eu.kanade.presentation.entries.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.AuroraTheme
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EditMetadataSheet(
    onDismissRequest: () -> Unit,
    currentTitle: String,
    currentAuthor: String?,
    currentArtist: String?,
    currentDescription: String?,
    currentGenre: List<String>?,
    currentStatus: Long?,
    hasArtist: Boolean,
    onSave: (
        title: String?,
        author: String?,
        artist: String?,
        description: String?,
        tags: List<String>?,
        status: Long?,
    ) -> Unit,
    onReset: () -> Unit,
) {
    val colors = AuroraTheme.colors
    var title by remember { mutableStateOf(currentTitle) }
    var author by remember { mutableStateOf(currentAuthor.orEmpty()) }
    var artist by remember { mutableStateOf(currentArtist.orEmpty()) }
    var description by remember { mutableStateOf(currentDescription.orEmpty()) }
    var tagsList by remember { mutableStateOf(currentGenre.orEmpty()) }
    var status by remember { mutableStateOf(currentStatus) }

    var statusExpanded by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    val statusOptions = listOf(
        null to stringResource(MR.strings.status_default),
        SManga.ONGOING.toLong() to stringResource(MR.strings.status_ongoing),
        SManga.COMPLETED.toLong() to stringResource(MR.strings.status_completed),
        SManga.LICENSED.toLong() to stringResource(MR.strings.status_licensed),
        SManga.PUBLISHING_FINISHED.toLong() to stringResource(MR.strings.status_publishing_finished),
        SManga.CANCELLED.toLong() to stringResource(MR.strings.status_cancelled),
        SManga.ON_HIATUS.toLong() to stringResource(MR.strings.status_on_hiatus),
        SManga.UNKNOWN.toLong() to stringResource(MR.strings.status_unknown),
    )

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = stringResource(MR.strings.action_reset_metadata),
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(MR.strings.action_reset_metadata_confirmation),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onReset()
                        onDismissRequest()
                    },
                ) {
                    Text(
                        text = stringResource(MR.strings.action_reset_metadata),
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(
                        text = stringResource(MR.strings.action_cancel),
                        color = colors.textSecondary,
                    )
                }
            },
            containerColor = colors.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = colors.surface.copy(alpha = 0.98f),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(MR.strings.action_edit_metadata),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                fontSize = 22.sp,
            )

            // Title OutlinedTextField
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(MR.strings.label_custom_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    focusedLabelColor = colors.accent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
            )

            // Author OutlinedTextField
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text(stringResource(MR.strings.label_custom_author)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    focusedLabelColor = colors.accent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
            )

            // Artist OutlinedTextField (only if hasArtist is true)
            if (hasArtist) {
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(MR.strings.label_custom_artist)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        focusedLabelColor = colors.accent,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
            }

            // Description OutlinedTextField
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(MR.strings.label_custom_description)) },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    focusedLabelColor = colors.accent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
            )

            // Status Dropdown Custom Picker
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(MR.strings.label_custom_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Box {
                    val currentStatusLabel = statusOptions.firstOrNull { it.first == status }?.second
                        ?: stringResource(MR.strings.status_default)
                    OutlinedButton(
                        onClick = { statusExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textPrimary,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentStatusLabel,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = colors.textSecondary,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = statusExpanded,
                        onDismissRequest = { statusExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f),
                    ) {
                        statusOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    status = value
                                    statusExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Tag/Genre Chip layout
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(MR.strings.label_custom_genres),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )

                OutlinedTextField(
                    value = newTagText,
                    onValueChange = { newTagText = it },
                    label = { Text(stringResource(MR.strings.genre_input_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newTagText.isNotBlank()) {
                                val trimmed = newTagText.trim()
                                if (!tagsList.contains(trimmed)) {
                                    tagsList = tagsList + trimmed
                                }
                                newTagText = ""
                            }
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        focusedLabelColor = colors.accent,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )

                if (tagsList.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tagsList.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(tag, color = colors.textPrimary) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.clickable {
                                            tagsList = tagsList.filter { it != tag }
                                        },
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = colors.surface.copy(alpha = 0.5f),
                                ),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sheet bottom buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        text = stringResource(MR.strings.action_reset_metadata),
                        fontWeight = FontWeight.Medium,
                    )
                }

                Button(
                    onClick = {
                        val finalTitle = title.trim().takeIf { it.isNotBlank() }
                        val finalAuthor = author.trim().takeIf { it.isNotBlank() }
                        val finalArtist = artist.trim().takeIf { it.isNotBlank() }
                        val finalDescription = description.trim().takeIf { it.isNotBlank() }
                        onSave(finalTitle, finalAuthor, finalArtist, finalDescription, tagsList, status)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = stringResource(MR.strings.action_save),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(MR.strings.action_cancel),
                    color = colors.textSecondary,
                )
            }
        }
    }
}
