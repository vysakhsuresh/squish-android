package com.squish.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.squish.app.editor.ProjectName
import com.squish.app.ui.theme.SquishColors

/**
 * Naming a project, from the editor's header or a card on the dashboard - one
 * dialog for one job. The dashboard had its own that prefilled the fallback
 * title as real text, so Save without typing named the project after its
 * first file and stopped it following that file. [current] is the name as
 * given, blank when there is none; [placeholder] is what shows then, the
 * first clip's name, and blank means no name again.
 */
@Composable
fun RenameDialog(current: String, placeholder: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)) {
        // In here, with the field: the dialog is its own window, composed after
        // the screen that asks for it, and focus asked for before the field is
        // there throws.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SquishColors.SurfaceElevated)
                .border(1.dp, SquishColors.Violet.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Name this project", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            OutlinedTextField(
                value = text,
                // A paste of a whole paragraph is cut back here rather than held
                // in the field - cut, not refused: a paste over the limit used to
                // do nothing at all. ProjectName decides the final length.
                onValueChange = { typed ->
                    val kept = ProjectName.cut(typed.text, ProjectName.FIELD_LENGTH)
                    text = if (kept.length == typed.text.length) typed
                    else TextFieldValue(kept, TextRange(kept.length))
                },
                singleLine = true,
                placeholder = { Text(placeholder, color = SquishColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text.text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SquishColors.Violet,
                    unfocusedBorderColor = SquishColors.Border,
                    focusedTextColor = SquishColors.TextPrimary,
                    unfocusedTextColor = SquishColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SquishOutlinedButton(text = "Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                SquishPrimaryButton(text = "Save", modifier = Modifier.weight(1f), onClick = { onSave(text.text) })
            }
        }
    }
}
