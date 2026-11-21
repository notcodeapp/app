package com.notcode.mobile.ui

import android.annotation.SuppressLint
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notcode.mobile.IdeViewModel
import com.notcode.mobile.desktop.*
import java.io.ByteArrayInputStream
import java.net.URI

@Composable
fun DesktopPanel(vm: IdeViewModel) {
    val controller = vm.desktop
    val state by controller.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.disconnect() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.disconnect() }
    }
    var invite by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL.take(80).ifBlank { "Android" }) }
    var section by remember { mutableIntStateOf(0) }
    var model by remember { mutableStateOf<DesktopModel?>(null) }
    var sendText by remember(state.selected?.session?.id) { mutableStateOf("") }
    var contextPath by remember(state.selected?.session?.id) { mutableStateOf("") }
    var previewPort by remember { mutableStateOf("3000") }
    var cli by remember { mutableStateOf("claude") }
    var cliVersion by remember { mutableStateOf("latest") }
    var openWhenCopied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.models) { if (model !in state.models) model = state.models.firstOrNull() }
    LaunchedEffect(state.connected) { if (state.connected && state.files.isEmpty()) controller.browse() }
    // «Скопировать и открыть»: как только копия проекта появилась — открываем её как обычный проект.
    LaunchedEffect(state.copy, openWhenCopied) {
        val copy = state.copy
        if (openWhenCopied && copy != null) {
            openWhenCopied = false
            vm.openDesktopCopy(copy)
        }
    }
    if (state.previewUrl != null) {
        BackHandler { controller.stopPreview() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                TextButton(onClick = controller::stopPreview) { Text("Закрыть preview") }
                Text(state.previewStatus, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            }
            DesktopPreviewWebView(requireNotNull(state.previewUrl), Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.connected) {
            PairingView(
                state = state, invite = invite, onInvite = { invite = it }, deviceName = deviceName,
                onDeviceName = { deviceName = it.filter { ch -> ch.code >= 32 }.take(80) },
                onPair = { controller.pair(invite, deviceName); invite = "" },
                onScannerError = controller::reportScannerError,
                onReconnect = controller::reconnect, onDisconnect = controller::disconnect, onForget = controller::forget,
            )
        } else {
            val canImport = !state.busy && vm.fileSystem != null && !vm.sshWorkspaceActive && !vm.agentRunning && !vm.busy
            // Шапка «проект с ПК» — как локальный проект, только источник другой.
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                        Text(state.connection?.projectName ?: "Проект ПК", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("по Wi-Fi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = controller::reconnect, enabled = !state.busy) { Text("Обновить") }
                        TextButton(onClick = controller::disconnect) { Text("Отключить") }
                        TextButton(onClick = controller::forget) { Text("Забыть ПК") }
                    }
                }
            }
            // Главная кнопка: открыть проект с ПК как свой.
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val copy = state.copy
                    if (copy == null) {
                        Button(
                            onClick = { openWhenCopied = true; controller.copyProject() },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (openWhenCopied) "Копирую и открываю…" else "Открыть проект на телефоне") }
                        Text(
                            "Текстовая копия без ключей, .git и зависимостей (файлы ≤1 МиБ). После копирования проект откроется здесь, как локальный.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = { vm.openDesktopCopy(copy) }, enabled = !vm.busy && !vm.agentRunning, modifier = Modifier.fillMaxWidth()) {
                            Text("Открыть в IDE")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = controller::syncProject, enabled = !state.busy && vm.documents.none { it.dirty }) { Text("Sync с ПК") }
                            TextButton(onClick = controller::copyProject, enabled = !state.busy) { Text("Перекопировать") }
                        }
                    }
                }
            }
            HorizontalDivider()
            val sections = listOf("Чаты", "Файлы", "Превью", "CLI Linux")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections) { title ->
                    val index = sections.indexOf(title)
                    FilterChip(selected = section == index, onClick = { section = index }, label = { Text(title) })
                }
            }
            val enabled = state.connected && !state.busy
            when (section) {
                0 -> {
                    Text("Чаты компьютера", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Диалоги проекта на ПК. Открывайте отсюда или переносите в историю телефона кнопкой ↓ — один чат за раз, без дублей.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.modelErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Модель для новых запросов к агенту ПК", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.models, key = { "${it.provider}:${it.id}" }) { entry ->
                            FilterChip(selected = model == entry, onClick = { model = entry }, label = { Text("${entry.provider} · ${entry.label}") })
                        }
                    }
                    Button(onClick = { model?.let(controller::createSession) }, enabled = enabled && model != null) { Text("Новый чат на ПК") }
                    if (state.sessions.isEmpty()) Text("Чатов на ПК пока нет.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.sessions.forEach { session ->
                        ListItem(
                            headlineContent = { Text(session.title, maxLines = 1) },
                            supportingContent = { Text("${session.provider} · ${session.model}") },
                            trailingContent = {
                                TextButton(
                                    onClick = { vm.exportDesktopChat(session.id) },
                                    enabled = canImport,
                                ) { Text("↓") }
                            },
                            modifier = Modifier.clickable(enabled = enabled) { controller.openSession(session.id) },
                        )
                    }
                    state.selected?.let { chat ->
                        HorizontalDivider()
                        Text(chat.session.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${chat.session.provider} · ${chat.session.model} · ${if (chat.running) "агент работает" else "ожидает"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (chat.messages.size > 200) Text("Показаны последние 200 сообщений из ${chat.messages.size}", style = MaterialTheme.typography.labelSmall)
                                chat.messages.takeLast(200).forEach { message ->
                                    ChatBubble(message.role, message.content)
                                }
                                if (state.liveActions.isNotEmpty()) {
                                    var expanded by remember { mutableStateOf(false) }
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = { expanded = !expanded }) {
                                            Text((if (expanded) "▾ " else "▸ ") + actionCountLabel(state.liveActions.size))
                                        }
                                        if (expanded) state.liveActions.forEach { action ->
                                            Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                                                Text("${action.name} · ${action.status}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                if (action.detail.isNotEmpty()) Text(action.detail, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                if (state.partialText.isNotEmpty()) ChatBubble("assistant", state.partialText, streaming = true)
                            }
                        }
                        Text(state.agentStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(sendText, { if (it.toByteArray().size <= 64 * 1024) sendText = it }, label = { Text("Сообщение агенту ПК") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(contextPath, { contextPath = it.take(1024) }, label = { Text("Контекст: путь файла на ПК (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.send(sendText, contextPath) }, enabled = enabled && sendText.isNotBlank() && state.pendingRequestId == null && !chat.running) { Text("Отправить") }
                            TextButton(onClick = controller::cancelAgent, enabled = enabled && chat.running) { Text("Остановить") }
                        }
                        state.pendingRequestId?.let { requestId ->
                            Text("Результат отправки пока неизвестен. Повтор использует тот же UUID: $requestId", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = controller::retrySend, enabled = enabled) { Text("Повторить без дублей") }
                        }
                        OutlinedButton(onClick = { vm.exportDesktopChat() }, enabled = canImport && !chat.running) { Text("Перенести этот чат в историю телефона") }
                    }
                }
                1 -> {
                    val editor = state.editor
                    if (editor == null) {
                        Text("Файлы проекта на ПК", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { controller.browse(state.directory.substringBeforeLast('/', "")) }, enabled = enabled && state.directory.isNotEmpty()) { Text(".. Назад") }
                            TextButton(onClick = { controller.browse(state.directory) }, enabled = enabled) { Text("Обновить") }
                        }
                        state.files.forEach { entry ->
                            ListItem(
                                headlineContent = { Text((if (entry.isDir) "Папка · " else "") + entry.name) },
                                modifier = Modifier.clickable(enabled = enabled) {
                                    if (entry.isDir) controller.browse(entry.path) else controller.openFile(entry.path)
                                },
                            )
                        }
                    } else {
                        val content = state.editorDraft ?: editor.content
                        var reloadConfirm by remember { mutableStateOf(false) }
                        Text(editor.path, style = MaterialTheme.typography.titleMedium)
                        Text("Открыт SHA-256: ${editor.hash}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(content, controller::updateEditorDraft, minLines = 12, maxLines = 24,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                        if (state.editorConflict) Text("409: версия ПК изменилась. Ваш текст сохранён в форме. Force overwrite отсутствует.", color = MaterialTheme.colorScheme.error)
                        Row {
                            Button(onClick = { controller.saveFile(content) }, enabled = enabled && !state.editorConflict) { Text("Сохранить") }
                            TextButton(onClick = { controller.saveFile(content, keepBoth = true) }, enabled = enabled) { Text("Сохранить обе копии") }
                        }
                        Row {
                            TextButton(onClick = { reloadConfirm = true }, enabled = enabled) { Text("Перезагрузить / закрыть") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }) { Text("Копировать текст") }
                        }
                        if (reloadConfirm) AlertDialog(onDismissRequest = { reloadConfirm = false }, title = { Text("Отбросить несохранённый текст?") }, text = { Text("Перед этим можно сохранить отдельной копией или скопировать текст.") },
                            confirmButton = { TextButton(onClick = { controller.openFile(editor.path); reloadConfirm = false }) { Text("Загрузить с ПК") } },
                            dismissButton = { TextButton(onClick = { controller.closeEditor(); reloadConfirm = false }) { Text("Закрыть файл") } })
                    }
                }
                2 -> {
                    Text("Превью приложения с ПК", style = MaterialTheme.typography.titleMedium)
                    Text("Запустите HTTP-сервер проекта в терминале ПК, разрешите его порт на ПК и откройте здесь. WebSocket и HMR не поддерживаются; это не экран Windows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(previewPort, { previewPort = it.filter(Char::isDigit).take(5) }, label = { Text("Разрешённый на ПК порт") }, singleLine = true)
                    Button(onClick = { previewPort.toIntOrNull()?.let(controller::startPreview) }, enabled = enabled && previewPort.toIntOrNull() in 1..65535) { Text("Открыть превью") }
                    Text(state.previewStatus, style = MaterialTheme.typography.bodySmall)
                }
                3 -> {
                    Text("Установить CLI в Linux-окружение телефона", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("claude", "codex", "opencode").forEach { adapter -> FilterChip(selected = cli == adapter, onClick = { cli = adapter }, label = { Text(adapter) }) }
                    }
                    OutlinedTextField(cliVersion, { cliVersion = it.take(64) }, label = { Text("Версия: latest или x.y.z") }, singleLine = true)
                    val command = runCatching { desktopCliInstallCommand(cli, cliVersion) }.getOrNull()
                    command?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace) } }
                    Text("Ключи ПК не копируются: после установки выполните login в PTY самостоятельно.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.installDesktopCli(cli, cliVersion) }, enabled = command != null && vm.fileSystem != null && !vm.sshWorkspaceActive && !vm.busy && !vm.agentRunning && !vm.interactiveRunning) { Text("Установить с подтверждением") }
                    TextButton(onClick = { command?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = command != null) { Text("Скопировать команду") }
                }
            }
        }
        if (state.transferReport.isNotBlank()) SelectionContainer { Text(state.transferReport, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = controller::disconnect) { Text("Отменить и отключиться") }
        }
    }
}

/** Пошаговое сопряжение: QR → подтверждение на ПК → готово. */
@Composable
private fun PairingView(
    state: DesktopPanelState,
    invite: String,
    onInvite: (String) -> Unit,
    deviceName: String,
    onDeviceName: (String) -> Unit,
    onPair: () -> Unit,
    onScannerError: (String) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    state.connection?.let { connection ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Сохранённый ПК", style = MaterialTheme.typography.titleMedium)
                Text(connection.projectName, style = MaterialTheme.typography.bodyMedium)
                Text(connection.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Отпечаток: ${connection.fingerprint.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onReconnect, enabled = !state.busy) { Text("Подключиться") }
                    TextButton(onClick = onDisconnect, enabled = !state.busy) { Text("Отключить") }
                    TextButton(onClick = onForget) { Text("Забыть") }
                }
            }
        }
        HorizontalDivider()
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Подключить компьютер", style = MaterialTheme.typography.headlineSmall)
            PairingStep(1, "На ПК включите «Связь с телефоном» и покажите QR-код")
            InvitationQrScanner(onResult = onInvite, onError = onScannerError)
            if (invite.isNotBlank()) Text("Приглашение получено ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            PairingStep(2, "Назовите устройство — так оно появится на ПК")
            OutlinedTextField(deviceName, onDeviceName, label = { Text("Имя устройства") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PairingStep(3, "Подтвердите запрос на компьютере (до 60 секунд)")
            Button(onClick = onPair, enabled = invite.isNotBlank() && deviceName.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) "Жду подтверждения ПК…" else "Связать")
            }
            var manualInvite by remember { mutableStateOf(false) }
            TextButton(onClick = { manualInvite = !manualInvite }) { Text(if (manualInvite) "Скрыть ручной ввод" else "Ввести приглашение вручную") }
            if (manualInvite) {
                OutlinedTextField(invite, { if (it.toByteArray().size <= 4096) onInvite(it) }, label = { Text("JSON приглашения") },
                    visualTransformation = PasswordVisualTransformation(), maxLines = 4, modifier = Modifier.fillMaxWidth())
                Button(onClick = onPair, enabled = invite.isNotBlank() && deviceName.isNotBlank() && !state.busy) { Text("Связать вручную") }
            }
            Text(
                "Одна доверенная Wi-Fi сеть, TLS с закреплённым сертификатом. Ключи и секреты ПК остаются на ПК.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairingStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(22.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { Text("$number", style = MaterialTheme.typography.labelMedium) }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Сообщения удалённого чата — пузырями, как в локальном чате. */
@Composable
private fun ChatBubble(role: String, content: String, streaming: Boolean = false) {
    val isUser = role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            if (isUser) "Вы" else if (streaming) "Агент · печатает" else "Агент",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            SelectionContainer { Text(content, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Separate WebView, no JavaScript interfaces, file/content access, mixed origins or control API. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DesktopPreviewWebView(url: String, modifier: Modifier) {
    val authority = remember(url) { URI(url).rawAuthority }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(url) { onDispose { webView?.stopLoading(); webView?.loadUrl("about:blank"); webView?.clearCache(true); webView?.destroy(); webView = null } }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            webView = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                private fun allowed(value: String): Boolean = runCatching { URI(value).let { it.scheme == "http" && it.rawAuthority == authority && it.rawUserInfo == null } }.getOrDefault(false)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = request == null || !allowed(request.url.toString())
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    if (request != null && allowed(request.url.toString())) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { handler?.cancel() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            }
            setDownloadListener { _, _, _, _, _ -> }
            loadUrl(url)
        }
    })
}
