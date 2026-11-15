package com.benim.glasslauncher

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

// ------------------------------------------------------------------ SES
// Dosya gerekmez: sesler kodla üretilir.
//  - Odak değişince: cam "tink" (gezdikçe pentatonik nota değişir, hafif melodi gibi)
//  - Seçince: su damlası "blup"
// Kapatmak için: SOUND_ENABLED = false
const val SOUND_ENABLED = true

class GlassSound(ctx: Context) {
    private val SR = 44100
    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val tickId: Int
    private val selectId: Int
    private val rates = floatArrayOf(1f, 1.125f, 1.25f, 1.5f, 1.667f)

    init {
        tickId = pool.load(wav(ctx, "tick.wav", tickPcm()).path, 1)
        selectId = pool.load(wav(ctx, "select.wav", dropPcm()).path, 1)
    }

    fun tick(i: Int) { if (SOUND_ENABLED) pool.play(tickId, 0.35f, 0.35f, 1, 0, rates[i % rates.size]) }
    fun select() { if (SOUND_ENABLED) pool.play(selectId, 0.5f, 0.5f, 1, 0, 1f) }
    fun release() = pool.release()

    private fun tickPcm(): ShortArray {
        val n = (SR * 0.2).toInt()
        return ShortArray(n) { i ->
            val t = i / SR.toDouble()
            val env = exp(-t * 36.0) * min(1.0, t / 0.002)
            val s = sin(2 * PI * 1318.0 * t) + 0.45 * sin(2 * PI * 1318.0 * 2.76 * t) * exp(-t * 30.0)
            (s * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun dropPcm(): ShortArray {
        val n = (SR * 0.38).toInt()
        var ph = 0.0
        return ShortArray(n) { i ->
            val t = i / SR.toDouble()
            val f = 420.0 + 520.0 * (1 - exp(-t * 18.0))
            ph += 2 * PI * f / SR
            val env = exp(-t * 9.0) * min(1.0, t / 0.004)
            val s = sin(ph) + 0.3 * sin(2 * PI * 2637.0 * t) * exp(-t * 20.0)
            (s * env * 0.55 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun wav(ctx: Context, name: String, pcm: ShortArray): File {
        val bb = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SR).putInt(SR * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { bb.putShort(it) }
        return File(ctx.cacheDir, name).also { it.writeBytes(bb.array()) }
    }
}

// ------------------------------------------------------------------ ACTIVITY
class MainActivity : ComponentActivity() {
    private val apps = mutableStateOf(listOf<AppItem>())
    private lateinit var sound: GlassSound

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sound = GlassSound(this)
        setContent {
            GlassLauncher(apps.value, onTick = { sound.tick(it) }) { pkg ->
                sound.select()
                packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
    }

    override fun onResume() { super.onResume(); apps.value = loadApps() }
    override fun onDestroy() { super.onDestroy(); sound.release() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val d = it.activityInfo.loadBanner(pm) ?: it.loadIcon(pm)
                AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName,
                    d.toBitmap(320, 180).asImageBitmap())
            }
            .sortedBy { it.label.lowercase() }
    }
}

// ------------------------------------------------------------------ ARKA PLAN
// Keskin hatlı şekiller: cam kartın arkasında bulanınca fark belli olsun diye.
private fun DrawScope.drawLiquid(p: Float, q: Float) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0B1020))
    fun disc(c1: Color, c2: Color, x: Float, y: Float, r: Float) = drawCircle(
        Brush.linearGradient(listOf(c1, c2), Offset(x - r, y - r), Offset(x + r, y + r)), r, Offset(x, y))
    disc(Color(0xFF6C5CE7), Color(0xFFA29BFE), w * (0.15f + 0.45f * p), h * (0.25f + 0.25f * q), h * 0.32f)
    disc(Color(0xFF00CEC9), Color(0xFF55EFC4), w * (0.85f - 0.45f * p), h * (0.7f - 0.25f * q), h * 0.38f)
    disc(Color(0xFFFF7675), Color(0xFFFDCB6E), w * (0.5f + 0.25f * q), h * (0.95f - 0.45f * p), h * 0.26f)
    drawCircle(Color.White.copy(0.22f), h * 0.45f, Offset(w * (0.6f - 0.3f * q), h * (0.3f + 0.2f * p)), style = Stroke(18f))
    drawCircle(Color.White.copy(0.15f), h * 0.2f, Offset(w * (0.2f + 0.2f * p), h * 0.8f), style = Stroke(10f))
    rotate(-22f, Offset(w / 2, h / 2)) {
        drawRoundRect(Color.White.copy(0.13f), Offset(w * (0.1f + 0.7f * q), -h * 0.3f),
            Size(70f, h * 1.8f), CornerRadius(35f))
        drawRoundRect(Color.White.copy(0.09f), Offset(w * (0.8f - 0.6f * p), -h * 0.3f),
            Size(40f, h * 1.8f), CornerRadius(20f))
    }
}

@Composable
fun GlassLauncher(apps: List<AppItem>, onTick: (Int) -> Unit, onOpen: (String) -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    val q by t.animateFloat(1f, 0f, infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Reverse), label = "q")

    // Arka planın BULANIK kopyası: tek sefer bulanır, tüm kartlar bundan dilim alır (hızlı).
    val blurLayer = rememberGraphicsLayer()
    SideEffect { blurLayer.renderEffect = BlurEffect(42f, 42f, TileMode.Clamp) }

    Box(Modifier.fillMaxSize().drawBehind {
        blurLayer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { drawLiquid(p, q) }
        drawLiquid(p, q)
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
            Clock()
            Spacer(Modifier.height(28.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(vertical = 24.dp, horizontal = 12.dp)
            ) {
                itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
                    GlassCard(app, i, blurLayer, onTick) { onOpen(app.pkg) }
                }
            }
        }
    }
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    Column {
        BasicText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            style = TextStyle(Color.White, 56.sp, FontWeight.Light))
        BasicText(SimpleDateFormat("d MMMM EEEE", Locale.getDefault()).format(now),
            style = TextStyle(Color.White.copy(0.75f), 20.sp))
    }
}

// ------------------------------------------------------------------ CAM KART
@Composable
fun GlassCard(app: AppItem, index: Int, backdrop: GraphicsLayer, onTick: (Int) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(28.dp)
    val fr = remember { FocusRequester() }

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 45L).coerceAtMost(600))
        enter.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        if (index == 0) runCatching { fr.requestFocus() }
    }
    val sheen = remember { Animatable(-0.6f) }
    LaunchedEffect(focused) {
        if (focused) { sheen.snapTo(-0.6f); sheen.animateTo(1.6f, tween(900, easing = FastOutSlowInEasing)) }
    }
    val scale by animateFloatAsState(if (focused) 1.14f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 260f), label = "scale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(350), label = "glow")

    Column(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = enter.value
                translationY = (1f - enter.value) * 60f
            }
            .shadow(if (focused) 26.dp else 4.dp, shape, ambientColor = Color.White, spotColor = Color.White)
            .clip(shape)
            .onGloballyPositioned { pos = it.positionInRoot() }
            // 1) Gerçek cam: arkadaki bulanık görüntü, hafif büyütülmüş (lens etkisi)
            .drawBehind {
                val c = Offset(pos.x + size.width / 2, pos.y + size.height / 2)
                translate(-pos.x, -pos.y) { scale(1.08f, 1.08f, pivot = c) { drawLayer(backdrop) } }
            }
            // 2) Hafif beyaz cam tonu + üstten ışık
            .background(Brush.verticalGradient(listOf(
                Color.White.copy(0.20f + 0.12f * glow), Color.White.copy(0.06f + 0.06f * glow))))
            // 3) Odaklanınca üzerinden süzülen parlama
            .drawWithContent {
                drawContent()
                val x = size.width * sheen.value
                drawRect(Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(0.38f), Color.Transparent),
                    Offset(x - 140f, 0f), Offset(x + 140f, size.height * 0.7f)))
            }
            // 4) Cam kenarı
            .border(1.5.dp, Brush.linearGradient(listOf(
                Color.White.copy(0.55f + 0.4f * glow), Color.White.copy(0.05f),
                Color.White.copy(0.25f + 0.35f * glow))), shape)
            .focusRequester(fr)
            .onFocusChanged {
                if (it.isFocused && !focused) onTick(index)
                focused = it.isFocused
            }
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Image(app.icon, app.label,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Crop)
        Spacer(Modifier.height(8.dp))
        BasicText(app.label,
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp).align(Alignment.CenterHorizontally),
            maxLines = 1, style = TextStyle(Color.White, 16.sp, FontWeight.Medium))
    }
}
