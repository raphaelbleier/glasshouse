package io.github.raphaelbleier.glasshouse

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import java.util.Date
import java.util.Locale

data class App(val pkg: String, val label: String, val icon: ImageBitmap?)

internal val Display = FontFamily(
    Font(R.font.display_regular, FontWeight.Normal),
    Font(R.font.display_medium, FontWeight.Medium),
    Font(R.font.display_semibold, FontWeight.SemiBold),
    Font(R.font.display_bold, FontWeight.Bold),
)
internal val TextFace = FontFamily(
    Font(R.font.text_regular, FontWeight.Normal),
    Font(R.font.text_medium, FontWeight.Medium),
    Font(R.font.text_semibold, FontWeight.SemiBold),
)

// Layout: rows live in the bottom half; the backdrop melts into blur just above them
private const val PANEL = 0.5f
private const val BLUR_FROM = 0.36f
private const val BLUR_TO = 0.56f
private val EDGE = 56.dp

// Clock capsule; its frosted base is painted inside the cached backdrop layer at exactly this spot
private val PILL_TOP = 30.dp
private val PILL_W = 112.dp
private val PILL_H = 46.dp

// Specular rim: light catches the top-left edge, a fainter bounce on the bottom-right
internal val RimFocused = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.85f), 0.35f to Color.White.copy(alpha = 0.25f),
    0.65f to Color.White.copy(alpha = 0.12f), 1f to Color.White.copy(alpha = 0.55f),
)
internal val Rim = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.40f), 0.35f to Color.White.copy(alpha = 0.08f),
    0.65f to Color.White.copy(alpha = 0.03f), 1f to Color.White.copy(alpha = 0.22f),
)
internal val TopHighlight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.4f to Color.Transparent)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home(
    plex: Plex?,
    shelf: Shelf?,
    error: String?,
    apps: List<App>,
    onOpen: (Media) -> Unit,
    onApp: (String) -> Unit,
) {
    val onDeckTitle = stringResource(R.string.row_on_deck)
    val recentTitle = stringResource(R.string.row_recent)
    val picksTitle = stringResource(R.string.row_picks)
    val appsTitle = stringResource(R.string.row_apps)
    var focused by remember { mutableStateOf<Pair<Media, String>?>(null) }
    var shown by remember { mutableStateOf<Pair<Media, String>?>(null) }
    val first = remember { FocusRequester() }
    val firstApp = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }

    // Follow focus with a short delay so scrolling through a row doesn't reload every backdrop
    LaunchedEffect(focused, shelf) {
        delay(220)
        shown = focused ?: shelf?.onDeck?.firstOrNull()?.let { it to onDeckTitle }
            ?: shelf?.recent?.firstOrNull()?.let { it to recentTitle }
    }
    val list = rememberLazyListState()
    // Start on the first card once; later refreshes must not yank focus away from where the user is
    LaunchedEffect(shelf) {
        if (placed) return@LaunchedEffect
        if (shelf != null) {
            // Rows may have appeared above the apps row; bring them into composition before focusing
            list.scrollToItem(0)
            delay(150)
            placed = runCatching { first.requestFocus() }.isSuccess
        } else {
            delay(150)
            runCatching { firstApp.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(plex, shown?.first?.art)
        Info(shown, Modifier.padding(start = EDGE, top = 48.dp).width(600.dp))
        Clock(Modifier.align(Alignment.TopEnd).padding(top = PILL_TOP, end = EDGE).size(PILL_W, PILL_H))

        // Rows keep the default scrolling; the column snaps the focused row (with its title) to the panel top
        val rowSpec = LocalBringIntoViewSpec.current
        val titleSpace = with(LocalDensity.current) { 46.dp.toPx() }
        val pivot = remember(titleSpace) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - titleSpace
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
            LazyColumn(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(PANEL),
                state = list,
                // Enough bottom room that even the last row can snap to the top of the panel
                contentPadding = PaddingValues(top = 10.dp, bottom = 300.dp),
            ) {
                if (error != null) item { Caption(error, Modifier.padding(start = EDGE, top = 8.dp)) }
                var firstUsed = false
                fun focusFirst(): Modifier = if (firstUsed) Modifier else Modifier.focusRequester(first).also { firstUsed = true }
                val appFocus = Modifier.focusRequester(firstApp).then(if (shelf?.onDeck.isNullOrEmpty() && shelf?.recent.isNullOrEmpty()) focusFirst() else Modifier)

                shelf?.let { s ->
                    if (s.onDeck.isNotEmpty()) {
                        val m0 = focusFirst()
                        row(onDeckTitle, rowSpec) {
                            itemsIndexed(s.onDeck, key = { _, m -> m.ratingKey }) { i, m ->
                                Landscape(plex, m, if (i == 0) m0 else Modifier, onOpen) { focused = m to onDeckTitle }
                            }
                        }
                    }
                    if (s.recent.isNotEmpty()) {
                        val m0 = focusFirst()
                        row(recentTitle, rowSpec) {
                            itemsIndexed(s.recent, key = { _, m -> m.ratingKey }) { i, m ->
                                Poster(plex, m, if (i == 0) m0 else Modifier, onOpen) { focused = m to recentTitle }
                            }
                        }
                    }
                    if (s.picks.isNotEmpty()) {
                        row(picksTitle, rowSpec) {
                            itemsIndexed(s.picks, key = { _, m -> m.ratingKey }) { _, m ->
                                Poster(plex, m, Modifier, onOpen) { focused = m to picksTitle }
                            }
                        }
                    }
                }
                row(appsTitle, rowSpec) {
                    itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app -> AppTile(app, if (i == 0) appFocus else Modifier) { onApp(app.pkg) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.row(title: String, spec: BringIntoViewSpec, content: LazyListScope.() -> Unit) {
    item(key = title) {
        Column {
            Text(
                title, color = Color.White.copy(alpha = 0.92f), fontFamily = Display, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(start = EDGE, top = 6.dp),
            )
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = EDGE, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun Backdrop(plex: Plex?, art: String?) {
    Crossfade(art, animationSpec = tween(450), label = "backdrop") { a ->
        // Flatten all backdrop layers into one cached texture: the stick's GPU can't redraw several full-screen layers per frame
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .background(Color(0xFF06070A)),
        ) {
            AsyncImage(plex?.image(a, 1280, 720), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().drawBehind {
                    // Keep text legible over bright artwork: overall dim plus a deep left-side scrim behind the info
                    drawRect(Color.Black.copy(alpha = 0.18f))
                    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.45f to Color.Black.copy(alpha = 0.45f), 0.75f to Color.Transparent))
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.22f to Color.Transparent))
                },
            )
            // Progressive blur: the server-blurred copy fades in towards the rows (masked in its own layer)
            AsyncImage(
                plex?.image(a, 480, 270, blur = 40), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(BLUR_FROM to Color.Transparent, BLUR_TO to Color.Black, startY = 0f, endY = size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            // Frosted base of the clock capsule: same blurred picture, clipped to the capsule
            AsyncImage(
                plex?.image(a, 480, 270, blur = 40), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    val r = RoundRect(
                        left = size.width - (EDGE + PILL_W).toPx(), top = PILL_TOP.toPx(),
                        right = size.width - EDGE.toPx(), bottom = (PILL_TOP + PILL_H).toPx(),
                        cornerRadius = CornerRadius(PILL_H.toPx() / 2),
                    )
                    clipPath(Path().apply { addRoundRect(r) }) { this@drawWithContent.drawContent() }
                },
            )
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
                },
            )
        }
    }
}

@Composable
private fun Info(shown: Pair<Media, String>?, modifier: Modifier) {
    Crossfade(shown, animationSpec = tween(380), label = "info") { pair ->
        val (m, source) = pair ?: return@Crossfade
        Column(modifier) {
            Text(
                source.uppercase(Locale.getDefault()), color = Color.White.copy(alpha = 0.6f), fontFamily = TextFace,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                m.title, color = Color.White, fontFamily = Display, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp, lineHeight = 46.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (m.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    m.subtitle, color = Color.White.copy(alpha = 0.9f), fontFamily = TextFace, fontSize = 17.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (m.meta.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    m.meta.split("  ·  ").forEach { Chip(it) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                m.summary, color = Color.White.copy(alpha = 0.78f), fontFamily = TextFace, fontSize = 15.sp,
                lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Chip(text: String) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(Color.Black.copy(alpha = 0.22f)).background(Color.White.copy(alpha = 0.08f)).border(BorderStroke(1.dp, Rim), shape)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White.copy(alpha = 0.92f), fontFamily = TextFace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val shape = RoundedCornerShape(50)
    Box(
        // Regular glass: a dark tint keeps the capsule readable whether the artwork behind is bright or dark
        modifier.clip(shape).background(Color.Black.copy(alpha = 0.28f)).background(Color.White.copy(alpha = 0.06f))
            .background(TopHighlight).border(BorderStroke(1.dp, Rim), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            DateFormat.getTimeFormat(LocalContext.current).format(now), color = Color.White, fontFamily = Display,
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
        )
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Color.White.copy(alpha = 0.7f), fontFamily = TextFace, fontSize = 14.sp, modifier = modifier)

// A focusable liquid-glass tile: specular rim, top highlight, soft lift shadow and a one-shot light sweep on focus
@Composable
private fun Glass(
    w: Dp,
    h: Dp,
    radius: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val lift by animateFloatAsState(
        if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "lift",
    )
    val sweep = remember { Animatable(-0.6f) }
    LaunchedEffect(focused) {
        if (focused) {
            sweep.snapTo(-0.6f)
            sweep.animateTo(1.6f, tween(950, easing = FastOutSlowInEasing))
        }
    }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.size(w, h).drawBehind {
            // Cheap soft shadow: a few stacked translucent rounded rects instead of a blurred shadow
            if (lift > 0f) {
                for (step in 1..8) {
                    val grow = (2.5f * step).dp
                    val alpha = 0.045f
                    val g = grow.toPx()
                    drawRoundRect(
                        Color.Black.copy(alpha = alpha * lift),
                        topLeft = Offset(-g, -g + 12.dp.toPx() * lift),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(radius.toPx() + g),
                    )
                }
            }
        },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().onFocusChanged { if (it.isFocused) onFocus() },
            interactionSource = source,
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Black.copy(alpha = 0.18f),
                focusedContainerColor = Color.White.copy(alpha = 0.10f),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Rim), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, RimFocused), shape = shape),
            ),
        ) {
            content()
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(TopHighlight)
                    if (focused) {
                        val x = size.width * sweep.value
                        val band = size.width * 0.35f
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                                start = Offset(x - band, 0f), end = Offset(x + band, size.height),
                            ),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun Landscape(plex: Plex?, m: Media, modifier: Modifier, onOpen: (Media) -> Unit, onFocus: () -> Unit) {
    Glass(240.dp, 135.dp, 16.dp, modifier, { onOpen(m) }, onFocus) {
        AsyncImage(plex?.image(m.still ?: m.art, 480, 270), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        Text(
            m.title, color = Color.White, fontFamily = TextFace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 15.dp),
        )
        if (m.progress > 0f) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.28f)),
            ) {
                Box(Modifier.fillMaxWidth(m.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
            }
        }
    }
}

@Composable
private fun Poster(plex: Plex?, m: Media, modifier: Modifier, onOpen: (Media) -> Unit, onFocus: () -> Unit) {
    Glass(116.dp, 174.dp, 14.dp, modifier, { onOpen(m) }, onFocus) {
        AsyncImage(plex?.image(m.poster, 232, 348), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun AppTile(app: App, modifier: Modifier, onClick: () -> Unit) {
    Glass(150.dp, 96.dp, 22.dp, modifier, onClick) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (app.icon != null) Image(app.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.height(8.dp))
            Text(app.label, color = Color.White, fontFamily = TextFace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
