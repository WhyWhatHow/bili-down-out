package cn.a10miaomiao.bilidown.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileNameInputDialog(
    showInputDialog: Boolean,
    fileName: String,
    confirmText: String,
    author: String = "",
    onDismiss: () -> Unit,
    onConfirm: (outFile: BiliDownOutFile) -> Unit,
) {
    var errorText by remember() {
        mutableStateOf("")
    }
    var value by remember(fileName) {
        mutableStateOf(TextFieldValue(text = fileName, selection = TextRange(fileName.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(showInputDialog) {
        if (showInputDialog) {
            launch {
                focusRequester.requestFocus()
            }
        }
    }

    fun handleConfirm() {
        // 统一清洗：去空格 + 去除文件系统非法字符（\/:*?"<>|）
        val cleaned = BiliDownOutFile.sanitizeFileName(value.text)
        if (cleaned.isBlank()) {
            errorText = "文件名不能为空"
        } else {
            val name = cleaned + ".mp4"
            val outFile = BiliDownOutFile(name, author)
            if (outFile.exists()) {
                errorText = "文件已存在"
            } else {
                onConfirm(outFile)
            }
        }
    }

    fun handleClearSpace() {
        val text = value.text.replace(" ", "")
        value = TextFieldValue(
            text = text,
            selection = TextRange(text.length)
        )
    }

    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = "输入文件名：") },
            text = {
                Column {
                    TextField(
                        label = {
                            Text(text = "文件名")
                        },
                        trailingIcon = {
                            Text(text = ".mp4")
                        },
                        supportingText = {
                            Text(text = errorText)
                        },
                        isError = errorText.isNotBlank(),
                        value = value,
                        onValueChange = {
                            value = it
                            errorText = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { handleConfirm() }
                        ),
                    )
                    // 「导出后删除源文件」由设置页全局控制：
                    // 已开启时不再弹出提示（用户已知情），未开启时无破坏性行为也无需提示。
                }
            },
            confirmButton = {
                TextButton(
                    onClick = ::handleConfirm,
                ) {
                    Text(confirmText)
                }
            },
            dismissButton = {
                Row() {
                    if (" " in value.text) {
                        TextButton(
                            onClick = ::handleClearSpace,
                        ) {
                            Text("清除空格")
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                    ) {
                        Text("取消")
                    }
                }
            }
        )
    }
}