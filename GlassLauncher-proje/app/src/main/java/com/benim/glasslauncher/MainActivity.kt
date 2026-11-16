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
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
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

// ------------------------------------------------------------------ ARKA PLAN: KARLI DAĞLAR
// Her şey kodla çizilir. t: 0..1 arasında döner (180 sn'de bir tur), par: gezinirken oluşan kayma.
private fun frac(x: Float) = x - kotlin.math.floor(x)

// Sivri tepeli dağ hattı (0..1). u'nun 1 birim kayması kusursuz döngü verir.
private fun ridge(u: Float, s: Int): Float {
    fun r(k: Int, ph: Float) = 1f - abs(sin(PI.toFloat() * (k * u + ph)))
    return 0.5f * r(3, s * 0.37f) + 0.3f * r(7, s * 0.61f) + 0.2f * r(13, s * 0.13f)
}

private fun DrawScope.drawRidge(shift: Float, seed: Int, baseY: Float, amp: Float,
                                snow: Color, mid: Color, low: Color) {
    val w = size.width
    val path = Path()
    path.moveTo(0f, size.height)
    var x = 0f
    while (x <= w + 8f) {
        path.lineTo(x, baseY - amp * ridge(x / w + shift, seed))
        x += 8f
    }
    path.lineTo(w, size.height)
    path.close()
    // Yüksek yerler kar beyazı, aşağısı kaya rengi
    drawPath(path, Brush.verticalGradient(
        0f to snow, 0.35f to snow, 0.55f to mid, 1f to low,
        startY = baseY - amp, endY = size.height))
}

private fun DrawScope.drawScene(t: Float, par: Float) {
    val w = size.width; val h = size.height
    val twoPi = 2f * PI.toFloat()

    // Gökyüzü (şafak)
    drawRect(Brush.verticalGradient(
        listOf(Color(0xFF0B1636), Color(0xFF28497C), Color(0xFFE9B7A3)), startY = 0f, endY = h * 0.75f))
    // Yıldızlar
    for (i in 0 until 40) {
        val a = 0.35f + 0.35f * sin(twoPi * (t * 6f + i * 0.1f))
        drawCircle(Color.White.copy(alpha = a), 1.6f,
            Offset(frac(i * 0.618f + 0.3f) * w, frac(i * 0.382f) * h * 0.4f))
    }
    // Ay ve parıltısı
    val mc = Offset(w * 0.8f, h * 0.17f)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(0.35f), Color.Transparent), center = mc, radius = h * 0.2f),
        h * 0.2f, mc)
    drawCircle(Color(0xFFFFF6E5), h * 0.045f, mc)

    // Uzak dağlar
    drawRidge(t * 1f + par * 0.3f, 1, h * 0.62f, h * 0.30f,
        Color(0xFFF4F8FF), Color(0xFF9DB4D3), Color(0xFF6B84AA))
    // Sis
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(0.28f)),
        startY = h * 0.5f, endY = h * 0.75f), Offset(0f, h * 0.5f), Size(w, h * 0.25f))
    // Süzülen bulutlar
    for (i in 0 until 3) {
        val cx = frac(i * 0.37f + t * (1 + i)) * (w + 600f) - 300f
        val cy = h * (0.22f + 0.1f * i)
        scale(1f, 0.3f, Offset(cx, cy)) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(0.22f), Color.Transparent),
                center = Offset(cx, cy), radius = 260f), 260f, Offset(cx, cy))
        }
    }
    // Orta ve yakın dağlar (daha hızlı kayarlar = derinlik hissi)
    drawRidge(t * 2f + par * 0.6f, 2, h * 0.76f, h * 0.30f,
        Color.White, Color(0xFF7D93B5), Color(0xFF3E5577))
    drawRidge(t * 3f + par * 1f, 3, h * 0.94f, h * 0.30f,
        Color.White, Color(0xFF52688C), Color(0xFF16233C))

    // Yağan kar
    for (i in 0 until 90) {
        val sx = frac(i * 0.618f + 0.13f); val sy = frac(i * 0.381f + 0.37f)
        val k = 18 + 9 * (i % 3)
        val y = frac(sy + t * k) * (h + 20f) - 10f
        val x = sx * w + sin(twoPi * (t * 10f + sx)) * 30f
        drawCircle(Color.White.copy(alpha = 0.55f + 0.1f * (i % 3)), 1.5f + (i % 3) * 1.2f, Offset(x, y))
    }
}

// ------------------------------------------------------------------ ANA EKRAN
private val CARD_W = 260.dp

@Composable
fun GlassLauncher(apps: List<AppItem>, onTick: (Int) -> Unit, onOpen: (String) -> Unit) {
    val tr = rememberInfiniteTransition(label = "bg")
    val t by tr.animateFloat(0f, 1f,
        infiniteRepeatable(tween(180000, easing = LinearEasing), RepeatMode.Restart), label = "t")

    // Yana gezindikçe dağlar da yavaşça kayar
    var focusIdx by remember { mutableIntStateOf(0) }
    val par by animateFloatAsState(focusIdx * 0.025f, tween(1200, easing = FastOutSlowInEasing), label = "par")

    // Arka planın BULANIK kopyası (liquid glass için)
    val blurLayer = rememberGraphicsLayer()
    SideEffect { blurLayer.renderEffect = BlurEffect(42f, 42f, TileMode.Clamp) }

    // Odaklanan kart ekranın ortasına akıcı şekilde kayar
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(focusIdx, apps.size) {
        if (apps.isNotEmpty()) {
            val vw = listState.layoutInfo.viewportSize.width
            val iw = with(density) { CARD_W.roundToPx() }
            listState.animateScrollToItem(focusIdx.coerceAtMost(apps.size - 1), -(vw / 2 - iw / 2))
        }
    }

    Box(Modifier.fillMaxSize().drawBehind {
        blurLayer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { drawScene(t, par) }
        drawScene(t, par)
    }) {
        Column(Modifier.fillMaxSize().padding(top = 36.dp, bottom = 24.dp)) {
            Box(Modifier.padding(start = 56.dp)) { Clock() }
            Spacer(Modifier.weight(1f))
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                contentPadding = PaddingValues(horizontal = 56.dp, vertical = 48.dp)
            ) {
                itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
                    GlassCard(app, i, blurLayer, { idx -> focusIdx = idx; onTick(idx) }) { onOpen(app.pkg) }
                }
            }
        }
    }
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val sh = Shadow(Color.Black.copy(0.5f), Offset(0f, 2f), 8f)
    Column {
        BasicText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            style = TextStyle(Color.White, 56.sp, FontWeight.Light, shadow = sh))
        BasicText(SimpleDateFormat("d MMMM EEEE", Locale.getDefault()).format(now),
            style = TextStyle(Color.White.copy(0.85f), 20.sp, shadow = sh))
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
            .width(CARD_W)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = enter.value
                translationX = (1f - enter.value) * 80f
            }
            .shadow(if (focused) 26.dp else 4.dp, shape, ambientColor = Color.White, spotColor = Color.White)
            .clip(shape)
            .onGloballyPositioned { pos = it.positionInRoot() }
            // 1) Gerçek cam: arkadaki bulanık dağ, hafif büyütülmüş (lens etkisi)
            .drawBehind {
                val c = Offset(pos.x + size.width / 2, pos.y + size.height / 2)
                translate(-pos.x, -pos.y) { scale(1.08f, 1.08f, pivot = c) { drawLayer(backdrop) } }
            }
            // 2) Karın üstünde yazı okunsun diye hafif koyu ton + beyaz cam ışığı
            .background(Color(0xFF0B1730).copy(alpha = 0.28f))
            .background(Brush.verticalGradient(listOf(
                Color.White.copy(0.20f + 0.12f * glow), Color.White.copy(0.05f + 0.06f * glow))))
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
            maxLines = 1,
            style = TextStyle(Color.White, 16.sp, FontWeight.Medium,
                shadow = Shadow(Color.Black.copy(0.5f), Offset(0f, 2f), 6f)))
    }
}
