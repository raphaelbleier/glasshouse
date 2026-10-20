package io.github.raphaelbleier.glasshouse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen(Prefs(this), versionName()) }
    }

    private fun versionName() = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
}

private sealed interface Page {
    data object Main : Page
    data object Apps : Page
    data object Url : Page
    data class Code(val code: String) : Page
    data class Pick(val servers: List<Pair<PlexAccount.Server, String>>) : Page
    data class Message(val text: String) : Page
}

private val MINUTE_STEPS = listOf(5, 10, 15, 30, 60)

@Composable
private fun SettingsScreen(prefs: Prefs, version: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Page>(Page.Main) }
    // Bumped after every change so rows re-read the preferences
    var revision by remember { mutableIntStateOf(0) }
    fun changed() {
        SystemSetup.apply(context)
        revision++
    }

    val failed = stringResource(R.string.plex_failed)
    val noServer = stringResource(R.string.plex_no_server)
    fun connect() = scope.launch {
        val account = PlexAccount(prefs.clientId)
        runCatching {
            val pin = withContext(Dispatchers.IO) { account.createPin() }
            page = Page.Code(pin.code)
            var token: String? = null
            var tries = 0
            while (token == null && tries++ < 150) { // the PIN stays valid for about five minutes
                if (page !is Page.Code) return@launch // user backed out
                token = withContext(Dispatchers.IO) { account.token(pin) }
                if (token == null) delay(2000)
            }
            val servers = withContext(Dispatchers.IO) {
                account.servers(token ?: error("timeout")).mapNotNull { s -> account.reachable(s)?.let { s to it } }
            }
            page = when (servers.size) {
                0 -> Page.Message(noServer)
                1 -> { save(prefs, servers[0]); changed(); Page.Main }
                else -> Page.Pick(servers)
            }
        }.onFailure { if (page is Page.Code) page = Page.Message(failed) }
    }

    BackHandler(enabled = page != Page.Main) { page = Page.Main }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF141A33), Color(0xFF0A0C16), Color(0xFF0B2230))),
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
            Column(Modifier.width(340.dp).padding(top = 24.dp, end = 32.dp)) {
                val icon = remember { context.packageManager.getApplicationIcon(context.packageName).toBitmap(160, 160).asImageBitmap() }
                Image(icon, null, Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)))
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.settings_title), color = Color.White, fontFamily = Display, fontSize = 26.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 31.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Glasshouse $version", color = Color.White.copy(alpha = 0.5f), fontFamily = TextFace, fontSize = 14.sp)
            }
            Box(Modifier.weight(1f)) {
                androidx.compose.runtime.key(revision) {
                    when (val p = page) {
                        Page.Main -> MainList(prefs, ::connect, { page = it }, ::changed)
                        Page.Apps -> AppsList(prefs, ::changed)
                        Page.Url -> UrlEditor(prefs.screensaverUrl) { prefs.screensaverUrl = it; changed(); page = Page.Main }
                        is Page.Code -> CodePanel(p.code)
                        is Page.Pick -> PickList(p.servers) { save(prefs, it); changed(); page = Page.Main }
                        is Page.Message -> Panel { Body(p.text) }
                    }
                }
            }
        }
    }
}

private fun save(prefs: Prefs, choice: Pair<PlexAccount.Server, String>) {
    prefs.server = choice.second.trimEnd('/')
    prefs.token = choice.first.token
    prefs.serverName = choice.first.name
    prefs.machineId = choice.first.machineId
}

@Composable
private fun MainList(prefs: Prefs, connect: () -> Unit, go: (Page) -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    fun flag(b: Boolean) = if (b) on else off

    LazyColumn(contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Section(stringResource(R.string.section_plex)) }
        item {
            SettingRow(
                stringResource(R.string.plex_account), prefs.serverName ?: stringResource(R.string.plex_not_connected),
                Modifier.focusRequester(first), onClick = connect,
            )
        }
        if (prefs.token != null) {
            item {
                SettingRow(stringResource(R.string.disconnect), "") {
                    prefs.server = null; prefs.token = null; prefs.serverName = null; changed()
                }
            }
        }

        item { Section(stringResource(R.string.section_home)) }
        item { SettingRow(stringResource(R.string.show_on_deck), flag(prefs.showOnDeck)) { prefs.showOnDeck = !prefs.showOnDeck; changed() } }
        item { SettingRow(stringResource(R.string.show_recent), flag(prefs.showRecent)) { prefs.showRecent = !prefs.showRecent; changed() } }
        item { SettingRow(stringResource(R.string.show_picks), flag(prefs.showPicks)) { prefs.showPicks = !prefs.showPicks; changed() } }
        item {
            val count = prefs.dockApps?.size
            SettingRow(stringResource(R.string.dock_apps), count?.let { stringResource(R.string.dock_apps_value, it) } ?: stringResource(R.string.dock_apps_auto)) { go(Page.Apps) }
        }

        item { Section(stringResource(R.string.section_screensaver)) }
        item {
            SettingRow(stringResource(R.string.screensaver_url), prefs.screensaverUrl.ifEmpty { stringResource(R.string.screensaver_off) }) { go(Page.Url) }
        }
        item {
            SettingRow(stringResource(R.string.screensaver_after), stringResource(R.string.minutes, prefs.screensaverMinutes)) {
                prefs.screensaverMinutes = MINUTE_STEPS[(MINUTE_STEPS.indexOf(prefs.screensaverMinutes) + 1) % MINUTE_STEPS.size]
                changed()
            }
        }
        item { SettingRow(stringResource(R.string.never_sleep), flag(prefs.neverSleep)) { prefs.neverSleep = !prefs.neverSleep; changed() } }

        item { Section(stringResource(R.string.section_system)) }
        if (SystemSetup.isFireTv(context)) {
            item { SettingRow(stringResource(R.string.home_override), flag(prefs.homeOverride)) { prefs.homeOverride = !prefs.homeOverride; changed() } }
        }
        item {
            val ok = SystemSetup.canWrite(context)
            SettingRow(
                stringResource(R.string.setup_permission),
                if (ok) stringResource(R.string.setup_ok) else stringResource(R.string.setup_missing, context.packageName),
            ) {}
        }
    }
}

@Composable
private fun AppsList(prefs: Prefs, changed: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val all = remember { MainActivity.tvApps(context) }
    var chosen by remember {
        mutableStateOf(prefs.dockApps ?: all.filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }.map { it.packageName })
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Section(stringResource(R.string.dock_apps)) }
        items(all, key = { it.packageName }) { app ->
            val selected = app.packageName in chosen
            SettingRow(
                pm.getApplicationLabel(app).toString(), if (selected) "✓" else "",
                if (app == all.first()) Modifier.focusRequester(first) else Modifier,
            ) {
                chosen = if (selected) chosen - app.packageName else chosen + app.packageName
                prefs.dockApps = chosen
                changed()
            }
        }
    }
}

@Composable
private fun UrlEditor(initial: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Panel {
        Body(stringResource(R.string.screensaver_url))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.screensaver_url_hint), color = Color.White.copy(alpha = 0.55f), fontFamily = TextFace, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        val shape = RoundedCornerShape(14.dp)
        BasicTextField(
            text, { text = it },
            Modifier.fillMaxWidth().focusRequester(focus).clip(shape).background(Color.Black.copy(alpha = 0.3f))
                .border(BorderStroke(1.dp, Rim), shape).padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = TextStyle(color = Color.White, fontFamily = TextFace, fontSize = 18.sp),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        )
    }
}

@Composable
private fun CodePanel(code: String) = Panel {
    Body(stringResource(R.string.plex_connect_hint))
    Spacer(Modifier.height(20.dp))
    Text(
        code, color = Color.White, fontFamily = Display, fontSize = 64.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 12.sp,
    )
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.plex_waiting), color = Color.White.copy(alpha = 0.6f), fontFamily = TextFace, fontSize = 15.sp)
}

@Composable
private fun PickList(servers: List<Pair<PlexAccount.Server, String>>, onPick: (Pair<PlexAccount.Server, String>) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Section(stringResource(R.string.plex_pick_server)) }
        items(servers) { s ->
            SettingRow(s.first.name, s.second, if (s == servers.first()) Modifier.focusRequester(first) else Modifier) { onPick(s) }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.padding(top = 24.dp).fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.07f)).background(TopHighlight)
            .border(BorderStroke(1.dp, Rim), shape).padding(36.dp),
    ) { content() }
}

@Composable
private fun Body(text: String) =
    Text(text, color = Color.White, fontFamily = TextFace, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp)

@Composable
private fun Section(title: String) = Text(
    title.uppercase(), color = Color.White.copy(alpha = 0.55f), fontFamily = TextFace, fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
)

// A glass list row: label on the left, current value on the right
@Composable
private fun SettingRow(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.92f),
            focusedContentColor = Color(0xFF0B0D14),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Rim), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, RimFocused), shape = shape),
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontFamily = TextFace, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                value, fontFamily = TextFace, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp).widthIn(max = 380.dp),
            )
        }
    }
}
