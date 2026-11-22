package com.xyzterm.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** One validated two-field dialog shared by snippets and custom binds. */
@Composable
fun TwoFieldDialog(
    firstLabel: String,
    secondLabel: String,
    initialFirst: String = "",
    initialSecond: String = "",
    validate: (String, String) -> String? = { _, _ -> null },
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "",
) {
    var first by remember { mutableStateOf(initialFirst) }
    var second by remember { mutableStateOf(initialSecond) }
    val error = validate(first, second)

    DoubleInputDialog(
        title = title,
        firstInputLabel = firstLabel,
        firstInputValue = first,
        onFirstInputValueChange = { first = it },
        secondInputLabel = secondLabel,
        secondInputValue = second,
        onSecondInputValueChange = { second = it },
        onConfirm = { onConfirm(first, second) },
        onDismiss = onDismiss,
        confirmEnabled = error == null,
        firstErrorMessage = error,
    )
}
