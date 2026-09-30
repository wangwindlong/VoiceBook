package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import us.wangxy.voicebook.bff.ContentApi
import us.wangxy.voicebook.bff.contract.InterestTag
import us.wangxy.voicebook.data.PersonalizationRepository
import us.wangxy.voicebook.ui.widget.BloomSheet

@Composable
fun InterestProfileSheet(onDismiss: () -> Unit, onboarding: Boolean = false) {
    val api=org.koin.compose.koinInject<ContentApi>()
    val preferences=org.koin.compose.koinInject<PersonalizationRepository>()
    val enabled by preferences.enabled.collectAsState()
    val user by preferences.user.collectAsState()
    val scope=rememberCoroutineScope()
    var tags by remember(user) { mutableStateOf<List<InterestTag>>(emptyList()) }
    var choices by remember(user) { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember(user) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(user) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(user) {
        preferences.load()
        if (user != null) try {
            tags=api.profile().tags
            choices=(api.tags().tags.map { it.name } + api.categories().values).distinct().sorted()
        } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error="暂时无法加载兴趣，请稍后重试" }
    }
    BloomSheet(visible=true,onDismiss=onDismiss,peekFraction=0.85f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if (onboarding) "选择感兴趣的话题" else "兴趣画像",style=MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("个性化推荐")
                Switch(enabled,onCheckedChange={ scope.launch { preferences.setEnabled(it) } })
            }
            Text("只调整推荐顺序；关闭后停止上报客户端行为。",style=MaterialTheme.typography.bodySmall)
            if (user == null) Text("登录后查看账号的兴趣画像")
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            tags.forEach { tag ->
                Text(tag.tag,style=MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress={tag.weight.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                Text(if(tag.muted) "已静音，不再累计" else if(tag.source=="manual") "来自你选择的兴趣" else "来自${if(tag.source=="book_tag") "书籍标签" else "订阅分类"}，${tag.evidence} 次行为贡献",style=MaterialTheme.typography.bodySmall)
                if (!tag.muted) TextButton(enabled=!saving,onClick={ scope.launch {
                    saving=true
                    try { api.muteInterest(tag.tag); tags=api.profile().tags }
                    catch(e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error="静音失败，请重试" }
                    finally { saving=false }
                } }) { Text("静音此标签") }
            }
            if(choices.isNotEmpty()) {
                Text("选择 3–5 个兴趣（可跳过）")
                choices.forEach { tag ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(tag in selected,onCheckedChange={ checked -> selected=if(checked && selected.size<5) selected+tag else selected-tag })
                        Text(tag,Modifier.padding(top=12.dp))
                    }
                }
                Button(enabled=!saving && selected.size in minOf(3,choices.size)..5,onClick={ scope.launch {
                    saving=true
                    try { api.selectInterests(selected.toList()); preferences.completeOnboarding(); onDismiss() }
                    catch(e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error="保存失败，请重试" }
                    finally { saving=false }
                } }) { Text("保存兴趣") }
            }
            TextButton(onClick={ scope.launch { preferences.completeOnboarding(); onDismiss() } }) { Text(if(onboarding) "暂时跳过" else "关闭") }
        }
    }
}
