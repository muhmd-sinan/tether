package dev.tether.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.tether.TetherViewModel
import dev.tether.net.AgentClient
import dev.tether.net.SessionConnection
import dev.tether.net.SessionInfo
import dev.tether.term.TerminalCanvasView
import kotlinx.coroutines.delay

private val Mono = TextStyle(fontFamily = FontFamily.Monospace)

// ---- connect -----------------------------------------------------------------

@Composable
fun ConnectScreen(vm: TetherViewModel) {
    var host by rememberSaveable { mutableStateOf(vm.lastHost) }
    var port by rememberSaveable { mutableStateOf(vm.port.toString()) }
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && portNum != null
    val connect = { if (valid) vm.connectTo(host.trim(), portNum!!) }

    Column(
        Modifier.fillMaxSize().background(Tokens.Bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Connect to your PC", style = MaterialTheme.typography.headlineSmall, color = Tokens.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the PC's Tailscale address. Run tailscale ip -4 on the PC to see it. " +
                "The Tether agent must be running there.",
            style = MaterialTheme.typography.bodyMedium, color = Tokens.Dim,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = host, onValueChange = { host = it.trim() },
            label = { Text("Tailscale address") }, placeholder = { Text("100.x.y.z or pc-name") },
            singleLine = true, textStyle = Mono,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") }, singleLine = true, textStyle = Mono,
            isError = port.isNotEmpty() && portNum == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = connect, enabled = valid, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Connect to PC")
        }
    }
}

// ---- main ----------------------------------------------------------------------

@Composable
fun MainScreen(vm: TetherViewModel) {
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var closeTarget by remember { mutableStateOf<SessionInfo?>(null) }

    Column(Modifier.fillMaxSize().background(Tokens.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(vm, onRename = { renameTarget = it }, onClose = { closeTarget = it })
        StatusLine(vm)
        if (vm.sessions.isEmpty()) {
            EmptyState(vm, Modifier.weight(1f))
        } else {
            SessionPager(vm, Modifier.weight(1f))
            ExtraKeys(vm)
        }
    }

    renameTarget?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { vm.renameSession(s.id, name.trim()); renameTarget = null },
                    enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    closeTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { closeTarget = null },
            title = { Text("Close ${s.name}?") },
            text = { Text("This stops the shell on the PC and anything running in it.") },
            confirmButton = {
                TextButton(onClick = { vm.closeSession(s.id); closeTarget = null }) {
                    Text("Close session", color = Tokens.Bad)
                }
            },
            dismissButton = { TextButton(onClick = { closeTarget = null }) { Text("Keep running") } },
        )
    }
}

@Composable
private fun TopBar(vm: TetherViewModel, onRename: (SessionInfo) -> Unit, onClose: (SessionInfo) -> Unit) {
    var addOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (vm.sessions.isEmpty()) {
                Text("Tether", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium,
                    color = Tokens.Ink)
            } else {
                SessionTabs(vm, onRename, onClose)
            }
        }
        Box {
            IconButton(onClick = { addOpen = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "New session", tint = Tokens.Ink)
            }
            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                DropdownMenuItem(text = { Text("New PowerShell") }, onClick = { addOpen = false; vm.newSession("powershell") })
                DropdownMenuItem(text = { Text("New cmd") }, onClick = { addOpen = false; vm.newSession("cmd") })
            }
        }
        Box {
            IconButton(onClick = { moreOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More options", tint = Tokens.Dim)
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                DropdownMenuItem(text = { Text("Change PC") }, onClick = { moreOpen = false; vm.forgetHost() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTabs(vm: TetherViewModel, onRename: (SessionInfo) -> Unit, onClose: (SessionInfo) -> Unit) {
    val index = vm.sessions.indexOfFirst { it.id == vm.selected }.coerceAtLeast(0)
    var menuFor by remember { mutableStateOf<String?>(null) }
    ScrollableTabRow(
        selectedTabIndex = index,
        containerColor = Tokens.Bg,
        contentColor = Tokens.Ink,
        edgePadding = 4.dp,
        divider = {},
        indicator = { positions ->
            if (index < positions.size) {
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[index]), color = Tokens.Accent)
            }
        },
    ) {
        vm.sessions.forEachIndexed { i, s ->
            val selected = i == index
            Box(
                Modifier
                    .height(48.dp)
                    .semantics { this.selected = selected; role = Role.Tab }
                    .combinedClickable(
                        onClick = { vm.selected = s.id },
                        onLongClick = { menuFor = s.id },
                        onLongClickLabel = "Session options",
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, color = if (selected) Tokens.Ink else Tokens.Dim,
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.pcViewers > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("PC", color = Tokens.Dim, style = MaterialTheme.typography.labelSmall)
                    }
                }
                DropdownMenu(expanded = menuFor == s.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename session") }, onClick = { menuFor = null; onRename(s) })
                    DropdownMenuItem(text = { Text("Close session", color = Tokens.Bad) },
                        onClick = { menuFor = null; onClose(s) })
                }
            }
        }
    }
}

@Composable
private fun StatusLine(vm: TetherViewModel) {
    val agent = vm.agent ?: return
    val err = agent.error
    LaunchedEffect(err) {
        if (err != null) {
            delay(4000)
            agent.error = null
        }
    }
    val offline = agent.link == AgentClient.Link.Offline && vm.sessions.isNotEmpty()
    if (err == null && !offline) return
    Row(
        Modifier.fillMaxWidth().background(Tokens.Surface).padding(horizontal = 16.dp).height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(err ?: "Can't reach the PC. Retrying.", color = if (err != null) Tokens.Bad else Tokens.Warn,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (err == null) TextButton(onClick = { vm.retry() }) { Text("Retry now") }
    }
}

@Composable
private fun EmptyState(vm: TetherViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (vm.agent?.link) {
            AgentClient.Link.Online -> {
                Text("No sessions yet", style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
                Spacer(Modifier.height(8.dp))
                Text("Start a shell on the PC. It keeps running when you leave the app, and the PC can open it from the tray.",
                    style = MaterialTheme.typography.bodyMedium, color = Tokens.Dim)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { vm.newSession("powershell") }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Start PowerShell")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.newSession("cmd") }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Start cmd")
                }
            }
            AgentClient.Link.Offline -> {
                Text("Can't reach the PC", style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
                Spacer(Modifier.height(8.dp))
                Text("Check that Tailscale is connected on both devices and the Tether agent is running on ${vm.host}.",
                    style = MaterialTheme.typography.bodyMedium, color = Tokens.Dim)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { vm.retry() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Try again") }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { vm.forgetHost() }, modifier = Modifier.fillMaxWidth()) { Text("Change PC") }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Tokens.Accent)
                Spacer(Modifier.width(12.dp))
                Text("Connecting to ${vm.host}", color = Tokens.Dim, style = Mono)
            }
        }
    }
}

// ---- terminal pages --------------------------------------------------------------

@Composable
private fun SessionPager(vm: TetherViewModel, modifier: Modifier) {
    val sessions = vm.sessions
    val index = sessions.indexOfFirst { it.id == vm.selected }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = index) { sessions.size }
    LaunchedEffect(index) {
        if (pager.currentPage != index) pager.animateScrollToPage(index)
    }
    val latest by rememberUpdatedState(sessions.toList())
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            latest.getOrNull(p)?.let { if (vm.selected != it.id) vm.selected = it.id }
        }
    }
    // Full-screen apps pan sideways, so swiping between tabs is off while one is showing.
    val alt = vm.current?.altScreen == true
    HorizontalPager(
        state = pager,
        modifier = modifier,
        key = { sessions.getOrNull(it)?.id ?: it },
        userScrollEnabled = !alt,
    ) { page ->
        val s = sessions.getOrNull(page) ?: return@HorizontalPager
        val conn = vm.connections[s.id] ?: return@HorizontalPager
        TerminalPage(vm, conn, focused = page == pager.currentPage)
    }
}

private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun TerminalPage(vm: TetherViewModel, conn: SessionConnection, focused: Boolean) {
    var view by remember { mutableStateOf<TerminalCanvasView?>(null) }
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollableState { d -> view?.scrollByPixels(d) ?: 0f }
    val alt = conn.altScreen

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> TerminalCanvasView(ctx).also { view = it } },
            update = { v ->
                v.sticky = vm.sticky
                v.connection = conn
                v.fontSizeSp = vm.fontSize
            },
            onRelease = { v -> v.connection = null },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Terminal. Tap to type, long press for paste." }
                .pinchToZoom { f ->
                    view?.let { v ->
                        v.fontSizeSp = v.fontSizeSp * f
                        vm.updateFontSize(v.fontSizeSp)
                    }
                }
                .then(
                    if (alt) Modifier.pointerInput(Unit) {
                        detectDragGestures { change, drag -> change.consume(); view?.panBy(drag.x, drag.y) }
                    } else Modifier.scrollable(scroll, Orientation.Vertical)
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { view?.showKeyboard() }, onLongPress = { menu = true })
                },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Paste") }, onClick = {
                menu = false
                clipboard.getText()?.text?.let(conn::paste)
            })
            DropdownMenuItem(text = { Text("Copy all output") }, onClick = {
                menu = false
                view?.transcript()?.let { clipboard.setText(AnnotatedString(it.trimEnd())) }
            })
        }
        when (conn.state) {
            SessionConnection.State.Connecting -> if (conn.emulator == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp,
                    color = Tokens.Accent)
            }
            SessionConnection.State.Reconnecting -> Text(
                "Reconnecting",
                color = Tokens.Warn,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(Tokens.Raised, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            SessionConnection.State.Ended -> Text("Session ended", color = Tokens.Dim,
                modifier = Modifier.align(Alignment.Center))
            SessionConnection.State.Live -> {}
        }
    }
    LaunchedEffect(focused, view) {
        if (focused) view?.requestFocus()
    }
}

// ---- extra keys ------------------------------------------------------------------

@Composable
private fun ExtraKeys(vm: TetherViewModel) {
    val keys = vm.sticky
    val send = { code: Int -> keys.sendKey(vm.current, code) }
    val type = { s: String -> keys.sendText(vm.current, s) }
    Row(Modifier.fillMaxWidth().height(48.dp).background(Tokens.Surface)) {
        Key("ESC", "Escape") { send(KeyEvent.KEYCODE_ESCAPE) }
        Key("TAB", "Tab") { send(KeyEvent.KEYCODE_TAB) }
        Key("CTRL", "Control", active = keys.ctrl) { keys.ctrl = !keys.ctrl }
        Key("ALT", "Alt", active = keys.alt) { keys.alt = !keys.alt }
        Key("←", "Left arrow") { send(KeyEvent.KEYCODE_DPAD_LEFT) }
        Key("↓", "Down arrow") { send(KeyEvent.KEYCODE_DPAD_DOWN) }
        Key("↑", "Up arrow") { send(KeyEvent.KEYCODE_DPAD_UP) }
        Key("→", "Right arrow") { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Key("|", "Pipe") { type("|") }
        Key("~", "Tilde") { type("~") }
        Key("/", "Slash") { type("/") }
        Key("-", "Dash") { type("-") }
    }
}

@Composable
private fun RowScope.Key(label: String, description: String, active: Boolean = false, onTap: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 4.dp)
            .background(if (active) Tokens.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .semantics { contentDescription = description; if (active) selected = true; role = Role.Button }
            .pointerInput(onTap) {
                detectTapGestures(onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Tokens.OnAccent else Tokens.Ink, maxLines = 1,
            style = Mono.copy(fontSize = if (label.length > 2) 11.sp else 16.sp))
    }
}
