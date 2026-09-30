package us.wangxy.voicebook.screens.library
import androidx.compose.runtime.Composable
@Composable
actual fun rememberBookPicker(onResult: (Result<SelectedBook>) -> Unit): () -> Unit =
    { onResult(Result.failure(UnsupportedOperationException("当前 iOS 构建尚未接入文件选择器，请使用 Android 或桌面端上传"))) }
