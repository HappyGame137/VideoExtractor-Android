package me.kitbox.videoextractor

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: ExtractorModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, 0xFFE5F4C7.toInt())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        if (savedInstanceState == null) acceptShare(intent)
        setContent {
            MaterialTheme(colorScheme = palette) {
                ExtractorScreen(model, ::paste, ::play, ::share)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            if (text.isNotBlank()) {
                if (model.progress == null) model.edit(text)
                else Toast.makeText(this, "请等待当前提取完成，再分享新链接。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun paste() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isNotBlank()) model.edit(text)
        else Toast.makeText(this, "剪贴板里没有文本链接", Toast.LENGTH_SHORT).show()
    }

    private fun play(video: SavedVideo) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(video.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchExternal(intent, "未找到可以播放 MP4 的应用")
    }

    private fun share(video: SavedVideo) {
        val intent = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, video.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                clipData = ClipData.newUri(contentResolver, video.name, video.uri)
            }
        launchExternal(Intent.createChooser(intent, "分享视频"), "没有可以接收视频的应用")
    }

    private fun launchExternal(intent: Intent, fallback: String) {
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, fallback, Toast.LENGTH_SHORT).show()
        }
    }
}

internal class ExtractorModel(application: Application) : AndroidViewModel(application) {
    private val extractor = VideoExtractor(application)
    private var task: Job? = null
    var input by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    var saved by mutableStateOf<SavedVideo?>(null)
        private set

    fun edit(value: String) {
        input = value.take(16_384)
        error = null
    }

    fun start() {
        if (task?.isActive == true) return
        val link = parseLink(input)
        if (link == null) {
            error = "没有找到可识别的视频链接。请粘贴完整分享文案或 HTTPS 的 MP4 地址。"
            return
        }
        progress = Progress("正在解析链接")
        error = null
        task = viewModelScope.launch {
            try {
                saved = extractor.extract(link) { progress = it }
            } catch (e: CancellationException) {
                error = "已取消提取。"
                throw e
            } catch (e: IOException) {
                error = when (e) {
                    is UnknownHostException -> "无法连接视频平台，请检查网络。"
                    is SocketTimeoutException -> "连接超时，请稍后重试。"
                    is VideoError, is VideoTooLarge, is WrongVideo -> e.message
                    else -> "读取或保存失败，请检查网络和手机剩余空间。"
                }
            } catch (_: SecurityException) {
                error = "无法写入系统相册。"
            } finally {
                progress = null
            }
        }
    }

    fun cancel() { task?.cancel() }
}

private val palette = lightColorScheme(
    primary = Color(0xFF214E45), onPrimary = Color.White,
    secondary = Color(0xFF49645B), background = Color(0xFFF6F8F2),
    surface = Color.White, onBackground = Color(0xFF172924),
    onSurface = Color(0xFF172924), onSurfaceVariant = Color(0xFF475952),
    outline = Color(0xFF788780), primaryContainer = Color(0xFFE5F4C7),
    onPrimaryContainer = Color(0xFF263421),
)

@Composable
private fun ExtractorScreen(model: ExtractorModel, paste: () -> Unit, play: (SavedVideo) -> Unit, share: (SavedVideo) -> Unit) {
    val focus = LocalFocusManager.current
    val progress = model.progress
    val available = progress == null
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 26.dp),
        ) {
            Image(painterResource(R.drawable.ic_video), contentDescription = null, Modifier.size(46.dp))
            Spacer(Modifier.height(18.dp))
            Text("视频提取", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("粘贴分享链接，把视频保存到相册。", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(25.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("视频来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = model.input, onValueChange = model::edit, enabled = available,
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                        shape = RoundedCornerShape(14.dp), label = { Text("链接或分享文案") },
                        placeholder = { Text("在原 App 复制分享链接后粘贴到这里") },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (model.input.isNotEmpty()) TextButton(onClick = { model.edit("") }, enabled = available) { Text("清空") }
                        TextButton(onClick = paste, enabled = available) { Text("粘贴") }
                    }
                    Button(
                        onClick = { focus.clearFocus(); model.start() },
                        enabled = available && model.input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text("提取并保存") }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(18.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(progress.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            TextButton(onClick = model::cancel) { Text("取消") }
                        }
                        if (progress.total > 0) {
                            val ratio = (progress.bytes.toDouble() / progress.total).toFloat().coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (progress.bytes > 0) Text(
                            fileSize(progress.bytes) + if (progress.total > 0) " / ${fileSize(progress.total)}" else "",
                            modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            model.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            model.saved?.let { video ->
                Spacer(Modifier.height(18.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("已保存到相册", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(video.name, style = MaterialTheme.typography.bodyMedium)
                        Text("Movies/视频提取 · ${fileSize(video.bytes)}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { play(video) }) { Text("播放") }
                            Button(onClick = { share(video) }) { Text("分享") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("支持的链接", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("哔哩哔哩 · 抖音 · 小红书 · MP4 直链", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text("仅提取可公开访问的视频。也可在其他应用的系统分享菜单里选择「视频提取」。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fileSize(bytes: Long): String = if (bytes >= 1_000_000)
    String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
else "${(bytes + 999) / 1_000} KB"
