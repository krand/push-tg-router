@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pushrouter.app.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.pushrouter.app.R
import com.pushrouter.app.notifications.RouterNotificationListener
import com.pushrouter.core.*
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label: String) { NOTIFICATIONS("Notifications"), ROUTES("Routes"), PAIRINGS("Pairings"), SETTINGS("Settings") }
private val Blue = Color(0xFF2456B8)

@Composable
fun RouterApp(model: RouterViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val verified by model.verifiedBot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.NOTIFICATIONS) }
    var access by remember { mutableStateOf(false) }
    var notification by remember { mutableStateOf<CapturedNotification?>(null) }
    var draft by remember { mutableStateOf<Route?>(null) }
    var invitationOpen by rememberSaveable { mutableStateOf(false) }
    var pairingToEdit by remember { mutableStateOf<Pairing?>(null) }
    var botConfirmation by remember { mutableStateOf(false) }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFA7C4FF)) else lightColorScheme(primary = Blue, background = Color(0xFFF7F8FA), surface = Color.White)

    LifecycleResumeEffect(Unit) {
        access = if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, RouterNotificationListener::class.java))
        else NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        model.action { model.repository.prune() }
        onPauseOrDispose { }
    }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(invitationOpen, lifecycle) {
        if (invitationOpen) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.startPolling()
            try { kotlinx.coroutines.awaitCancellation() } finally { model.stopPolling() }
        } else model.stopPolling()
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.app_icon), contentDescription = null, modifier = Modifier.size(38.dp))
                        Text("Push Router", style = MaterialTheme.typography.titleLarge)
                    }
                }, actions = { TextButton(onClick = { model.action { model.repository.pause(!state.paused) } }, enabled = !busy) { Text(if (state.paused) "Resume" else "Pause") } })
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = {
                            Icon(when (item) {
                                Tab.NOTIFICATIONS -> Icons.Outlined.Notifications
                                Tab.ROUTES -> Icons.AutoMirrored.Outlined.CallSplit
                                Tab.PAIRINGS -> Icons.Outlined.PeopleOutline
                                Tab.SETTINGS -> Icons.Outlined.Settings
                            }, contentDescription = null)
                        }, label = { Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.NOTIFICATIONS -> NotificationsScreen(state, access, onOpen = { notification = it }, onSettings = { tab = Tab.SETTINGS })
                    Tab.ROUTES -> RoutesScreen(state, edit = { draft = it }, create = {
                        val source = state.notifications.firstOrNull { !it.contentUnavailable && it.forwardingBlockedReason == null }
                        if (source != null) draft = newRoute(source)
                        else { tab = Tab.NOTIFICATIONS }
                    }, toggle = { id, enabled -> model.action { model.repository.toggleRoute(id, enabled) } })
                    Tab.PAIRINGS -> PairingsScreen(state, add = { if (state.bot == null) tab = Tab.SETTINGS else invitationOpen = true }, manage = { pairingToEdit = it })
                    Tab.SETTINGS -> SettingsScreen(state, access, busy, verified,
                        verify = model::verifyBot,
                        useBot = { if (state.bot != null && verified?.id != state.bot?.id) botConfirmation = true else model.useVerifiedBot() },
                        dismissVerified = model::discardVerifiedBot,
                        clearHistory = { model.action { model.repository.clearHistory() } })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if (message != null) AlertDialog(onDismissRequest = model::dismissMessage, title = { Text("Could not complete action") }, text = { Text(message!!) }, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK") } })
        notification?.let { n ->
            NotificationDialog(n, state, dismiss = { notification = null }, create = { draft = newRoute(n); notification = null })
        }
        draft?.let { route ->
            RouteEditor(route, state, dismiss = { draft = null }, save = { model.action { model.repository.saveRoute(it); draft = null; tab = Tab.ROUTES } }, delete = { model.action { model.repository.deleteRoute(route.id); draft = null } })
        }
        if (invitationOpen) PairingDialog(state, busy,
            dismiss = { invitationOpen = false; model.action { model.repository.cancelInvitation() } },
            invite = { label -> model.action { model.repository.invite(label); model.startPolling() } },
            confirm = { model.action { model.repository.confirmRecipient(); invitationOpen = false } },
            reject = { model.action { model.repository.cancelInvitation() } })
        pairingToEdit?.let { p -> PairingEditor(p, state,
            dismiss = { pairingToEdit = null },
            rename = { label -> model.action { model.repository.renamePairing(p.id, label); pairingToEdit = null } },
            remove = { model.action { model.repository.removePairing(p.id); pairingToEdit = null } }) }
        if (botConfirmation) AlertDialog(onDismissRequest = { botConfirmation = false }, title = { Text("Replace router bot?") }, text = {
            Text("This disconnects ${state.pairings.size} pairing(s) and pauses all routes. Pair recipients with the new bot before resuming.")
        }, confirmButton = { TextButton(onClick = { model.useVerifiedBot(); botConfirmation = false }) { Text("Replace bot") } }, dismissButton = { TextButton(onClick = { botConfirmation = false }) { Text("Keep current bot") } })
    }
}

private fun newRoute(n: CapturedNotification) = Route(name = "${n.source.appName} · ${n.categoryName}", packageName = n.source.packageName, profile = n.source.profile, channelId = n.channelId)

@Composable private fun Heading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun EmptyState(text: String) { Text(text, Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable private fun NotificationsScreen(state: RouterState, access: Boolean, onOpen: (CapturedNotification) -> Unit, onSettings: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val shown = state.notifications.filter { n ->
        val sent = state.deliveries.any { it.notificationId == n.id && it.status == DeliveryStatus.SENT }
        when (filter) { "Forwarded" -> sent; "Not forwarded" -> !sent; else -> true }
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Heading("Notifications", "Captured on this phone · last 24 hours") }
        item {
            AppCard {
                Text(if (!access) "Notification access is off" else if (state.paused) "Forwarding paused · still collecting" else "Listening for new notifications")
                if (!access) TextButton(onClick = onSettings) { Text("Enable in Settings") }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Forwarded", "Not forwarded").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }) } } }
        if (shown.isEmpty()) item { EmptyState(if (state.notifications.isEmpty()) "Notifications will appear here after you allow access. Nothing is forwarded until you create a route." else "No notifications in this filter.") }
        items(shown, key = { it.id }) { n ->
            OutlinedCard(onClick = { onOpen(n) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(n.source.appName, fontWeight = FontWeight.Medium); Text(n.categoryName, style = MaterialTheme.typography.bodySmall) }
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.receivedAt)), style = MaterialTheme.typography.bodySmall)
                    }
                    if (n.title.isNotBlank()) Text(n.title, fontWeight = FontWeight.Medium)
                    Text(if (n.contentUnavailable) "Content unavailable" else n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    Text(deliveryLabel(state, n.id), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun deliveryLabel(state: RouterState, id: String): String {
    val deliveries = state.deliveries.filter { it.notificationId == id }
    val sent = deliveries.filter { it.status == DeliveryStatus.SENT }
    if (sent.isNotEmpty()) return "Forwarded → " + sent.joinToString { d -> state.pairings.find { it.id == d.pairingId }?.label ?: "Removed pairing" } + if (sent.size < deliveries.size) " · other deliveries pending or stopped" else ""
    return when {
        deliveries.any { it.status == DeliveryStatus.UNKNOWN } -> "Delivery unconfirmed"
        deliveries.any { it.status == DeliveryStatus.SENDING } -> "Sending"
        deliveries.any { it.status == DeliveryStatus.QUEUED } -> "Queued"
        deliveries.any { it.status == DeliveryStatus.FAILED } -> "Not forwarded · delivery failed"
        else -> "Not forwarded"
    }
}

@Composable private fun NotificationDialog(n: CapturedNotification, state: RouterState, dismiss: () -> Unit, create: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(n.source.appName) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(n.title, fontWeight = FontWeight.Medium)
            Text(if (n.contentUnavailable) "Android did not expose usable notification content." else n.text)
            n.forwardingBlockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(deliveryLabel(state, n.id))
            Text("Category: ${n.categoryName}\nAndroid profile: ${n.source.profile}", style = MaterialTheme.typography.bodySmall)
            state.deliveries.filter { it.notificationId == n.id }.forEach { delivery ->
                Text("${state.pairings.find { it.id == delivery.pairingId }?.label ?: "Removed pairing"}: ${delivery.status.name.lowercase()}${delivery.explanation?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Routes match the app and Android profile. Categories do not reliably identify accounts inside the app.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = create, enabled = !n.contentUnavailable && n.forwardingBlockedReason == null) { Text("Route notifications like this") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable private fun RoutesScreen(state: RouterState, edit: (Route) -> Unit, create: () -> Unit, toggle: (String, Boolean) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Heading("Routes", "Match notifications. Choose their destinations.") }
        item { Button(onClick = create) { Icon(Icons.Outlined.Add, null); Text("Create route") } }
        if (state.routes.isEmpty()) item { EmptyState("Capture a notification, then use it to create your first route.") }
        items(state.routes, key = { it.id }) { route -> AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(route.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(checked = route.enabled, onCheckedChange = { toggle(route.id, it) }, enabled = route.pairingIds.isNotEmpty())
            }
            Text("${state.notifications.find { it.source.packageName == route.packageName }?.source?.appName ?: route.packageName} · ${route.channelId ?: "Any category"}", style = MaterialTheme.typography.bodySmall)
            if (route.contains.isNotBlank()) Text("${route.field.name.lowercase()} contains “${route.contains}”", style = MaterialTheme.typography.bodySmall)
            Text("→ " + route.pairingIds.mapNotNull { id -> state.pairings.find { it.id == id }?.label }.joinToString().ifBlank { "Choose a pairing" })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (!route.enabled) "Paused" else if (state.paused) "Global forwarding paused" else "Active · future notifications", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { edit(route) }) { Text("Edit") } }
        } }
        item { Text("Several matching rules send one copy per destination. Unmatched notifications are not forwarded.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun RouteEditor(initial: Route, state: RouterState, dismiss: () -> Unit, save: (Route) -> Unit, delete: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var test by remember { mutableStateOf(false) }
    var selectedTest by remember { mutableStateOf(state.notifications.firstOrNull()?.id) }
    var deleting by remember { mutableStateOf(false) }
    val sources = (state.notifications.map { it.source } + Source(initial.packageName, initial.profile, state.notifications.find { it.source.packageName == initial.packageName }?.source?.appName ?: initial.packageName)).distinctBy { it.packageName to it.profile }
    val categories = state.notifications.filter { it.source.packageName == draft.packageName && it.source.profile == draft.profile }.distinctBy { it.channelId }
    val validTargets = state.pairings.filter { it.botId == state.bot?.id }
    val testNotification = state.notifications.find { it.id == selectedTest }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (test) "Test route · preview only" else if (state.routes.any { it.id == initial.id }) "Edit route" else "Create route") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (test) {
                Dropdown("Sample notification", state.notifications.map { it.id to "${it.source.appName} · ${it.title}" }, selectedTest.orEmpty()) { selectedTest = it }
                testNotification?.let { n -> Text(n.title); Text(n.text); Text(if (RouterEngine.matches(draft, n)) "Matches → ${draft.pairingIds.mapNotNull { id -> state.pairings.find { it.id == id }?.label }.joinToString().ifBlank { "Choose a pairing" }}" else "Does not match. Nothing would be forwarded.") }
                Text("This test sends nothing. Paused routes and global pause stop actual forwarding.", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(60)) }, label = { Text("Route name") }, singleLine = true)
                Dropdown("Source app · Android profile", sources.map { "${it.packageName}|${it.profile}" to "${it.appName} · profile ${it.profile}" }, "${draft.packageName}|${draft.profile}") { value -> val s = sources.first { "${it.packageName}|${it.profile}" == value }; draft = draft.copy(packageName = s.packageName, profile = s.profile, channelId = null) }
                Dropdown("Notification category", listOf("" to "Any category") + categories.map { it.channelId to it.categoryName }, draft.channelId.orEmpty()) { draft = draft.copy(channelId = it.takeIf(String::isNotBlank)) }
                Dropdown("Text condition", TextField.entries.map { it.name to when (it) { TextField.TITLE -> "Title contains"; TextField.MESSAGE -> "Message contains"; TextField.EITHER -> "Title or message contains" } }, draft.field.name) { draft = draft.copy(field = TextField.valueOf(it)) }
                OutlinedTextField(draft.contains, { draft = draft.copy(contains = it.take(100)) }, label = { Text("Optional matching text") }, singleLine = true)
                Text("Forward to", fontWeight = FontWeight.Medium)
                if (validTargets.isEmpty()) Text("Add a Telegram pairing before saving this route.")
                validTargets.forEach { p -> Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(pairingIds = if (p.id in draft.pairingIds) draft.pairingIds - p.id else draft.pairingIds + p.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = p.id in draft.pairingIds, onCheckedChange = { checked -> draft = draft.copy(pairingIds = if (checked) draft.pairingIds + p.id else draft.pairingIds - p.id) })
                    Column { Text(p.label); Text(p.displayName, style = MaterialTheme.typography.bodySmall) }
                } }
                Text("${state.notifications.count { RouterEngine.matches(draft, it) }} of ${state.notifications.size} captured notifications match. Saving affects future notifications only.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { test = true }, enabled = state.notifications.isNotEmpty()) { Text("Test with a sample") }
                if (state.routes.any { it.id == initial.id }) TextButton(onClick = { deleting = true }) { Text("Delete route", color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { if (test) test = false else save(draft) }, enabled = test || (draft.name.isNotBlank() && draft.pairingIds.isNotEmpty() && draft.pairingIds.all { id -> validTargets.any { it.id == id } })) { Text(if (test) "Back to route" else "Save route") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete route?") }, text = { Text("Queued deliveries that no longer match another active route will be stopped.") }, confirmButton = { TextButton(onClick = delete) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep route") } })
}

@Composable private fun Dropdown(label: String, options: List<Pair<String, String>>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(options.find { it.first == selected }?.second ?: selected, Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) { options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { change(value); expanded = false }) } }
        }
    }
}

@Composable private fun PairingsScreen(state: RouterState, add: () -> Unit, manage: (Pairing) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Heading("Pairings", "Telegram accounts you can route to.") }
        item { Button(onClick = add) { Icon(Icons.Outlined.Add, null); Text(if (state.bot == null) "Configure bot first" else "Add pairing") } }
        if (state.pairings.isEmpty()) item { EmptyState("No accounts paired. Create an invitation and confirm the recipient’s identity.") }
        items(state.pairings, key = { it.id }) { p -> AppCard {
            Text(p.label, fontWeight = FontWeight.Medium)
            Text("${p.displayName}${p.username?.let { " · @$it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Paired · ${state.routes.count { p.id in it.pairingIds }} route(s)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { manage(p) }) { Text("Manage") } }
        } }
        item { Text("Labels are for you. Pairings are bound to confirmed Telegram identities through @${state.bot?.username ?: "your bot"}.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun PairingDialog(state: RouterState, busy: Boolean, dismiss: () -> Unit, invite: (String) -> Unit, confirm: () -> Unit, reject: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    val invitation = state.invitation
    val recipient = invitation?.recipient
    val context = LocalContext.current
    val bot = state.bot
    val link = if (invitation != null && bot != null) "https://t.me/${bot.username}?start=${invitation.token}" else null
    val bitmap = remember(link) { link?.let { qrBitmap(it) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(invitation?.token) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (recipient != null) "Confirm recipient" else "Add pairing") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recipient != null) {
                Text(recipient.displayName, style = MaterialTheme.typography.titleMedium)
                Text(recipient.username?.let { "@$it" } ?: "No public username")
                Text("Telegram identity: ${recipient.userId}", style = MaterialTheme.typography.bodySmall)
                Text("Label: ${invitation.label}")
                Text("Check that this is the intended person. No notifications are forwarded until you assign this pairing to a route.")
            } else if (invitation != null && link != null) {
                Text("@${state.bot?.username}")
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = "QR invitation to pair with the Telegram bot", modifier = Modifier.size(210.dp)) }
                Text("Scan on the recipient’s phone, then tap Start in Telegram.")
                Text("Expires in ${maxOf(0, (invitation.expiresAt - now) / 1000)} seconds", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) { Text("Open Telegram on this phone") }
                Text("Waiting for recipient…", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label, e.g. Work phone") }, singleLine = true)
                Text("An invitation expires after five minutes and connects one recipient. Keep this screen open while they join.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        if (recipient != null) TextButton(onClick = confirm, enabled = !busy && now < invitation.expiresAt) { Text("Confirm pairing") }
        else if (invitation == null) TextButton(onClick = { invite(label) }, enabled = !busy && label.isNotBlank()) { Text("Create invitation") }
    }, dismissButton = { TextButton(onClick = if (recipient != null) reject else dismiss) { Text(if (recipient != null) "Reject recipient" else "Cancel") } })
}

private fun qrBitmap(link: String): Bitmap {
    val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
}

@Composable private fun PairingEditor(pairing: Pairing, state: RouterState, dismiss: () -> Unit, rename: (String) -> Unit, remove: () -> Unit) {
    var label by rememberSaveable(pairing.id) { mutableStateOf(pairing.label) }
    var removing by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (removing) "Remove pairing?" else "Manage pairing") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${pairing.displayName}${pairing.username?.let { " · @$it" }.orEmpty()}")
            if (removing) Text("Queued deliveries to this person stop. Routes left without a destination are paused. A message already being sent cannot be recalled.")
            else {
                OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label") }, singleLine = true)
                Text("Used by ${state.routes.count { pairing.id in it.pairingIds }} route(s).", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { removing = true }) { Text("Remove pairing", color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(onClick = { if (removing) remove() else rename(label) }, enabled = removing || label.isNotBlank()) { Text(if (removing) "Remove" else "Save label") } }, dismissButton = { TextButton(onClick = { if (removing) removing = false else dismiss() }) { Text(if (removing) "Keep pairing" else "Cancel") } })
}

@Composable private fun SettingsScreen(state: RouterState, access: Boolean, busy: Boolean, verified: Bot?, verify: (String) -> Unit, useBot: () -> Unit, dismissVerified: () -> Unit, clearHistory: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Settings", "Notification access and your Telegram bot.")
        AppCard {
            Text("Notification access", fontWeight = FontWeight.Medium)
            Text(if (access) "Allowed · listening for new notifications" else "Off · no new notifications collected")
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open Android settings") }
        }
        AppCard {
            Text("Telegram bot", fontWeight = FontWeight.Medium)
            Text(state.bot?.let { "@${it.username}" } ?: "No bot configured")
            Text("Use a dedicated bot for this phone. Create one with @BotFather and enter its token. Other bot clients or webhooks cannot share the pairing poller.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(token, { token = it.take(200); dismissVerified() }, label = { Text("Bot token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { verify(token); token = "" }, enabled = token.isNotBlank() && !busy) { Text("Verify token") }
            verified?.let { bot ->
                Text("Verified: @${bot.username}")
                Text(if (state.bot?.id != null && state.bot?.id != bot.id) "Switching bots requires pairing your recipients again." else "Token is encrypted on this phone using Android Keystore.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = useBot, enabled = !busy) { Text("Use this bot") }
                TextButton(onClick = dismissVerified) { Text("Discard") }
            }
        }
        AppCard {
            Text("Notification history", fontWeight = FontWeight.Medium)
            Text("Stored encrypted on this phone for up to 24 hours, limited to 500 notifications. Android cloud backup is disabled.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clearing = true }) { Text("Clear history") }
        }
        Text("Android may hide sensitive content. Only posted notifications can be captured; silent push payloads are unavailable.", style = MaterialTheme.typography.bodySmall)
        Text("Telegram and Telegram X notifications are collected but kept local to prevent forwarding loops.", style = MaterialTheme.typography.bodySmall)
        Text("Forwarding runs on this phone and needs connectivity. Network interruptions can leave delivery unconfirmed; those messages are not automatically resent.", style = MaterialTheme.typography.bodySmall)
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear notification history?") }, text = { Text("This removes saved notifications and stops their queued deliveries. Routes and pairings are kept.") }, confirmButton = { TextButton(onClick = { clearHistory(); clearing = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } })
}
