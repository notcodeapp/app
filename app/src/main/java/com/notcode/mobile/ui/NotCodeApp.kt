package com.notcode.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.notcode.mobile.*
import com.notcode.mobile.R
import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.model.*
import com.notcode.mobile.runtime.RuntimeState
import kotlinx.coroutines.launch

private val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Soft: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Night = Color(0xFF17201D)
private val Manrope = FontFamily(Font(R.font.manrope))

internal fun markdownBold(source: String): AnnotatedString = buildAnnotatedString {
    val marker = Regex("\\*\\*(.+?)\\*\\*")
    var cursor = 0
    marker.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(match.groupValues[1])
        pop()
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotCodeApp(vm: IdeViewModel) {
    val themeMode by vm.themeMode.collectAsState()
    val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFC4F16B), onPrimary = Color(0xFF20251F),
        background = Color(0xFF121713), surface = Color(0xFF171E18), onSurface = Color(0xFFE0E8DE),
        secondaryContainer = Color(0xFF29392C), onSecondaryContainer = Color(0xFFD7E8CD),
        onSurfaceVariant = Color(0xFFB6C2B3), outlineVariant = Color(0xFF3D493E)
    ) else lightColorScheme(
        primary = Color(0xFF2B7656), background = Color(0xFFFAFBF8), surface = Color(0xFFFAFBF8),
        onSurface = Color(0xFF17221D), secondaryContainer = Color(0xFFEAF1E8),
        onSecondaryContainer = Color(0xFF20452D), outlineVariant = Color(0xFFDDE4DB)
    )
    val previewState by vm.preview.state.collectAsState()
    val view = LocalView.current
    SideEffect {
        var context: Context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(bodyLarge = TextStyle(fontFamily = Manrope, fontSize = 15.sp), titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp))) {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addProject) }
        val snackbar = remember { SnackbarHostState() }
        var newFile by remember { mutableStateOf(false) }
        var showSshScreen by rememberSaveable { mutableStateOf(false) }
        var renamePath by remember { mutableStateOf<ProjectPath?>(null) }
        val canEdit = !vm.busy && !vm.agentRunning
        val rename: (ProjectPath) -> Unit = { if (canEdit) renamePath = it }
        val delete: (ProjectPath) -> Unit = { if (canEdit) vm.deleteFile(it) }
        LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
        BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
        var lastBackAt by remember { mutableLongStateOf(0L) }
        BackHandler {
            when {
                showSshScreen -> showSshScreen = false
                drawer.isOpen -> scope.launch { drawer.close() }
                previewState.url != null -> vm.preview.close()
                vm.selectedDocument != null -> vm.navigateBackFromFile()
                vm.tab != 0 -> vm.tab = 0
                else -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastBackAt < 2000L) (view.context as? Activity)?.moveTaskToBack(true)
                    else {
                        lastBackAt = now
                        Toast.makeText(view.context, "Нажмите «Назад» ещё раз, чтобы свернуть (терминал продолжит работу)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 340.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ПРОВОДНИК", style = MaterialTheme.typography.labelLarge, color = Green, modifier = Modifier.weight(1f))
                    IconButton(onClick = { newFile = true }, enabled = vm.hasActiveWorkspace && canEdit) { Icon(Icons.Outlined.NoteAdd, "Новый файл") }
                }
                OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Открыть папку") }
                ListItem(
                    headlineContent = { Text("SSH-подключение") },
                    supportingContent = { Text(if (vm.sshConnected) "${vm.sshProfile?.username}@${vm.sshProfile?.host}" else "Файлы и ИИ на сервере") },
                    leadingContent = { Icon(Icons.Outlined.Dns, null, tint = Green) },
                    modifier = Modifier.clickable { showSshScreen = true; scope.launch { drawer.close() } },
                )
                Text(vm.activeWorkspaceLabel, Modifier.padding(20.dp), fontWeight = FontWeight.Bold)
                if (vm.filesLoading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!vm.folder.isRoot) ListItem(headlineContent = { Text(".. / Назад") }, leadingContent = { Icon(Icons.Outlined.ArrowBack, null) }, modifier = Modifier.clickable { vm.browse(vm.folder.parent) })
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.entries, key = { it.path.value }) { entry ->
                        ListItem(headlineContent = { Text(entry.name, maxLines = 1) }, leadingContent = { Icon(if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = if (entry.isDirectory) Green else Muted) }, supportingContent = { if (!entry.isDirectory) Text("${entry.size} байт") }, trailingContent = { if (!entry.isDirectory && entry.editable) FileActions(entry.path, canEdit, rename, delete) }, modifier = Modifier.clickable {
                            if (entry.isDirectory) vm.browse(entry.path) else { vm.openFile(entry.path); scope.launch { drawer.close() } }
                        })
                    }
                }
                HorizontalDivider()
                Text("НЕДАВНИЕ ПРОЕКТЫ", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = Muted)
                val projects by vm.projects.collectAsState()
                LazyColumn(Modifier.heightIn(max = 180.dp)) { items(projects, key = { it.id }) { record ->
                    ListItem(headlineContent = { Text(record.displayName) }, leadingContent = { Icon(Icons.Outlined.FolderOpen, null) }, modifier = Modifier.clickable { vm.chooseProject(record); scope.launch { drawer.close() } })
                } }
            }
        }) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                Column {
                    TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "NotCode", modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(9.dp)); Text("notcode", fontWeight = FontWeight.ExtraBold); Text(".", color = Green, fontWeight = FontWeight.ExtraBold)
                    } }, navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Файлы проекта") } }, actions = {
                        IconButton(onClick = { vm.sync() }, enabled = vm.hasActiveWorkspace && !vm.agentRunning && !vm.busy) { Icon(Icons.Outlined.Sync, "Обновить файлы") }
                    })
                    Row(Modifier.fillMaxWidth().background(Soft).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = Green)
                        Text(if (vm.hasActiveWorkspace) vm.activeWorkspaceLabel else "Ваш код. Всегда с вами.", Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp, color = Green)
                        if (vm.hasActiveWorkspace) Text(if (vm.sshWorkspaceActive) "SSH" else "РАБОЧАЯ КОПИЯ", fontSize = 9.sp, color = Green)
                    }
                    if (vm.busy || vm.filesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, bottomBar = {
                NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                    listOf("Код" to Icons.Outlined.Code, "Агент" to Icons.Outlined.AutoAwesome, "Терминал" to Icons.Outlined.Terminal, "Настройки" to Icons.Outlined.Tune).forEachIndexed { index, (title, icon) ->
                        NavigationBarItem(selected = vm.tab == index, onClick = { vm.tab = index }, icon = { Icon(icon, title) }, label = { Text(title, fontSize = 10.sp) })
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    if (showSshScreen) {
                        SshConnectionScreen(vm, onOpenEditor = { showSshScreen = false; vm.tab = 0 }, onOpenAgent = { showSshScreen = false; vm.tab = 1 })
                    } else if (previewState.url != null) {
                        com.notcode.mobile.browser.BrowserScreen(vm.preview, onSavePage = { title, html -> vm.savePreviewPage(title, html) }, onClose = { vm.preview.close() })
                    } else when (vm.tab) {
                        0 -> EditorScreen(vm, { picker.launch(null) }, { scope.launch { drawer.open() } }, { newFile = true }, rename, delete)
                        1 -> AgentScreen(vm)
                        2 -> key("terminal-screen") { TerminalScreen(vm) }
                        else -> SettingsScreen(vm)
                    }
                }
            }
        }
        if (newFile) {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { newFile = false }, title = { Text("Новый файл") }, text = { OutlinedTextField(name, { name = it }, label = { Text("Путь относительно текущей папки") }, singleLine = true) }, confirmButton = { TextButton(onClick = { vm.createFile(name); newFile = false }, enabled = name.isNotBlank() && canEdit) { Text("Создать") } }, dismissButton = { TextButton(onClick = { newFile = false }) { Text("Отмена") } })
        }
        renamePath?.let { path ->
            var target by remember(path) { mutableStateOf(path.value) }
            AlertDialog(onDismissRequest = { renamePath = null }, title = { Text("Переименовать файл") }, text = {
                OutlinedTextField(target, { target = it }, label = { Text("Путь относительно корня проекта") }, singleLine = true, enabled = canEdit)
            }, confirmButton = {
                TextButton(onClick = { vm.renameFile(path, target); renamePath = null }, enabled = canEdit && target.isNotBlank() && target != path.value) { Text("Переименовать") }
            }, dismissButton = { TextButton(onClick = { renamePath = null }) { Text("Отмена") } })
        }
        vm.approval?.let { request ->
            var rememberForProject by remember(request) { mutableStateOf(false) }
            Dialog(onDismissRequest = { vm.decide(false) }) {
                Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Outlined.VerifiedUser, null, tint = Green)
                        Text(request.title, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text("Последнее слово — за вами.", color = Muted, fontSize = 12.sp)
                        Text("Команды выполняются с правами приложения: могут менять и удалять файлы и обращаться к сети. Папка проекта — не граница безопасности.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.weight(1f).fillMaxWidth().background(Night, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                            val before = request.before.lines()
                            val after = request.after.lines()
                            val prefix = before.zip(after).takeWhile { it.first == it.second }.size
                            val suffix = before.drop(prefix).asReversed().zip(after.drop(prefix).asReversed()).takeWhile { it.first == it.second }.size
                            before.take(prefix).takeLast(3).forEach { DiffLine("  $it", Color(0xFF97A99C)) }
                            before.drop(prefix).dropLast(suffix).forEach { DiffLine("− $it", Color(0xFFF4A09B)) }
                            after.drop(prefix).dropLast(suffix).forEach { DiffLine("+ $it", Color(0xFFA6D9B0)) }
                            if (suffix > 0) after.takeLast(suffix).take(3).forEach { DiffLine("  $it", Color(0xFF97A99C)) }
                        }
                        if (request.allowProjectTrust) {
                            Row(Modifier.fillMaxWidth().clickable { rememberForProject = !rememberForProject }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = rememberForProject, onCheckedChange = { rememberForProject = it })
                                Text("Больше не спрашивать в этом проекте", fontSize = 12.sp)
                            }
                            Text("Только для текущего проекта. Отозвать доверие можно в настройках.", color = Muted, fontSize = 11.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.decide(false) }) { Text("Отклонить") }
                            Button(onClick = { vm.decide(true, rememberForProject = request.allowProjectTrust && rememberForProject) }) { Text("Подтвердить") }
                        }
                    }
                }
            }
        }
        vm.sshHostKeyFingerprint?.let { fingerprint ->
            AlertDialog(
                onDismissRequest = { vm.decideSshHostKey(false) },
                icon = { Icon(Icons.Outlined.VerifiedUser, null, tint = Green) },
                title = { Text(if (vm.sshHostKeyChanged) "Ключ SSH-сервера изменился" else "Новый ключ SSH-сервера") },
                text = {
                    Column {
                        Text(if (vm.sshHostKeyChanged) "Ранее сохранённый ключ отличается. Это бывает после переустановки или смены ключа сервера, но может указывать и на подмену. Сверь новый отпечаток с администратором." else "Сверь отпечаток с администратором сервера. После подтверждения этот ключ будет сохранён на устройстве и проверяться при следующих подключениях.")
                        Text(fingerprint, Modifier.padding(top = 12.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                },
                confirmButton = { Button(onClick = { vm.decideSshHostKey(true) }) { Text(if (vm.sshHostKeyChanged) "Заменить ключ" else "Доверять ключу") } },
                dismissButton = { TextButton(onClick = { vm.decideSshHostKey(false) }) { Text("Отклонить") } },
            )
        }
    }
}

@Composable
private fun FileActions(path: ProjectPath, enabled: Boolean, rename: (ProjectPath) -> Unit, delete: (ProjectPath) -> Unit) {
    var expanded by remember(path) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Действия с ${path.name}") }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { expanded = false; rename(path) })
            DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { expanded = false; delete(path) })
        }
    }
}

@Composable
private fun SshConnectionScreen(vm: IdeViewModel, onOpenEditor: () -> Unit, onOpenAgent: () -> Unit) {
    val saved = vm.sshProfile
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf("/") }
    var authType by remember { mutableStateOf(SshAuthType.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    LaunchedEffect(saved?.id, saved?.host, saved?.username, saved?.remoteDirectory, saved?.port, saved?.authType) {
        if (saved != null) {
            name = saved.name; host = saved.host; port = saved.port.toString()
            username = saved.username; directory = saved.remoteDirectory; authType = saved.authType
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp)) {
        Text("SSH-подключение", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Открой серверную папку, редактируй файлы и работай с ИИ прямо на сервере.", color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        if (vm.sshConnected) {
            Surface(color = Soft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Подключено: ${vm.connectedSshProfile?.username}@${vm.connectedSshProfile?.host}:${vm.connectedSshProfile?.port}", fontWeight = FontWeight.SemiBold, color = Green)
                    Text("Папка: ${vm.sshFileSystem?.rootPath}", color = Muted, fontSize = 12.sp)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.activateSshWorkspace() }, enabled = !vm.sshWorkspaceActive && !vm.busy) { Text("Открыть SSH-файлы") }
                        TextButton(onClick = { vm.disconnectSsh() }, enabled = !vm.busy && !vm.agentRunning) { Text("Отключиться") }
                    }
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Название подключения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it }, label = { Text("Сервер или IP") }, placeholder = { Text("example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Порт") }, singleLine = true, modifier = Modifier.weight(0.35f))
            OutlinedTextField(username, { username = it }, label = { Text("Пользователь") }, singleLine = true, modifier = Modifier.weight(0.65f))
        }
        OutlinedTextField(directory, { directory = it }, label = { Text("Папка проекта на сервере") }, placeholder = { Text("/home/user/project") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Способ входа", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = authType == SshAuthType.PASSWORD, onClick = { authType = SshAuthType.PASSWORD }, label = { Text("Пароль") })
            FilterChip(selected = authType == SshAuthType.PRIVATE_KEY, onClick = { authType = SshAuthType.PRIVATE_KEY }, label = { Text("Закрытый ключ") })
        }
        if (authType == SshAuthType.PASSWORD) {
            OutlinedTextField(password, { password = it }, label = { Text(if (vm.sshConnected) "Пароль (оставь пустым, чтобы не менять)" else "Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(privateKey, { privateKey = it }, label = { Text("Закрытый ключ OpenSSH") }, visualTransformation = PasswordVisualTransformation(), minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Пароль ключа, если задан") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Text("Учётные данные шифруются Android Keystore. При первом входе нужно сверить отпечаток ключа сервера.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        Button(
            onClick = {
                vm.connectSsh(SshConnectionProfile(
                    id = saved?.id ?: java.util.UUID.randomUUID().toString(), name = name,
                    host = host, port = port.toIntOrNull() ?: 0, username = username,
                    remoteDirectory = directory, authType = authType,
                ), password, privateKey, passphrase)
                password = ""; privateKey = ""; passphrase = ""
            },
            enabled = !vm.busy && !vm.agentRunning && host.isNotBlank() && username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.sshConnected) "Сохранить и переподключиться" else "Сохранить и подключиться") }
        if (vm.sshWorkspaceActive) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenEditor, modifier = Modifier.weight(1f)) { Text("Файлы") }
                Button(onClick = onOpenAgent, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Работать с ИИ") }
            }
            OutlinedTextField(command, { command = it }, label = { Text("Команда на сервере") }, placeholder = { Text("например: git status") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), minLines = 1, maxLines = 3)
            Button(onClick = { vm.command(command); command = "" }, enabled = command.isNotBlank() && !vm.busy && !vm.agentRunning, modifier = Modifier.fillMaxWidth()) { Text("Выполнить с подтверждением") }
            Text(vm.terminal.takeLast(5000), Modifier.fillMaxWidth().padding(top = 10.dp).background(Night, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState()).padding(12.dp), color = Color(0xFFD9E8D7), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable private fun DiffLine(text: String, color: Color) { Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

@Composable
private fun EditorScreen(vm: IdeViewModel, open: () -> Unit, files: () -> Unit, newFile: () -> Unit, rename: (ProjectPath) -> Unit, delete: (ProjectPath) -> Unit) {
    val canEdit = !vm.busy && !vm.agentRunning
    val doc = vm.selectedDocument
    if (doc == null && vm.hasActiveWorkspace) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Файлы проекта", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = newFile, enabled = canEdit) { Icon(Icons.Outlined.NoteAdd, "Новый файл") }
                TextButton(onClick = files) { Text("Проводник") }
            }
            Text(vm.folder.value.ifBlank { "/" }, Modifier.padding(horizontal = 16.dp), color = Muted, fontSize = 12.sp)
            if (vm.filesLoading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.folder.isRoot) TextButton(onClick = { vm.browse(vm.folder.parent) }) { Text(".. / Назад") }
            if (vm.entries.isEmpty()) Text(if (vm.busy || vm.filesLoading) "Загружаем файлы…" else "Папка пуста. Создайте файл в проводнике.", Modifier.padding(20.dp), color = Muted)
            LazyColumn(Modifier.weight(1f)) {
                items(vm.entries, key = { it.path.value }) { entry ->
                    ListItem(headlineContent = { Text(entry.name) }, leadingContent = { Icon(if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, null) }, trailingContent = { if (!entry.isDirectory && entry.editable) FileActions(entry.path, canEdit, rename, delete) }, modifier = Modifier.clickable {
                        if (entry.isDirectory) vm.browse(entry.path) else vm.openFile(entry.path)
                    })
                }
            }
        }
        return
    }
    if (doc == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("МАЛЕНЬКИЙ ЭКРАН. БОЛЬШИЕ ИДЕИ.", fontSize = 10.sp, color = Green, letterSpacing = 1.sp)
            Text("Ваш код.\nВаш ИИ.\nВсегда с вами.", Modifier.padding(top = 18.dp, bottom = 16.dp), fontSize = 38.sp, lineHeight = 44.sp, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold)
            Text("${if (!vm.hasActiveWorkspace) "Откройте папку проекта или подключитесь к серверу по SSH." else "Проект открыт. Выберите файл в проводнике или создайте новый."}", color = Muted, lineHeight = 23.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = if (!vm.hasActiveWorkspace) open else files, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 15.dp)) { Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text(if (!vm.hasActiveWorkspace) "Открыть проект" else "Выбрать файл") }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = Green); Text("Ваши файлы. Ваш ключ. Ваш контроль.", Modifier.padding(start = 8.dp), color = Muted, fontSize = 11.sp) }
        }
        return
    }
    val canEditDocument = canEdit && !doc.readOnly
    Column(Modifier.fillMaxSize().imePadding()) {
        val tabsState = rememberLazyListState()
        LaunchedEffect(doc.path.value, vm.documents.size) {
            val index = vm.documents.indexOfFirst { it.path == doc.path }
            if (index >= 0) tabsState.animateScrollToItem(index)
        }
        LazyRow(state = tabsState, modifier = Modifier.fillMaxWidth().testTag("editor-tabs"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(vm.documents, key = { it.path.value }) { item ->
                Surface(color = if (item.path == doc.path) Soft else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.selectDocument(item) }, modifier = Modifier.testTag("file-tab:${item.path.value}")) {
                            Text(item.path.name + if (item.dirty) " •" else "", fontSize = 12.sp)
                        }
                        IconButton(onClick = { vm.closeDocument(item) }, enabled = canEdit) { Icon(Icons.Outlined.Close, "Закрыть ${item.path.name}", Modifier.size(18.dp)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = files) { Icon(Icons.Outlined.FolderOpen, "Открыть список файлов") }
            Text(doc.path.value, Modifier.weight(1f).testTag("selected-file"), fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!doc.readOnly && doc.path.name.substringAfterLast('.').lowercase() in setOf("html", "htm")) IconButton(onClick = { vm.previewFile(doc.path) }, enabled = !doc.dirty) { Icon(Icons.Outlined.PlayArrow, "Открыть HTML в браузере") }
            IconButton(onClick = newFile, enabled = canEdit) { Icon(Icons.Outlined.NoteAdd, "Новый файл") }
            if (!doc.readOnly || doc.allowFileOperations) FileActions(doc.path, canEdit, rename, delete)
            TextButton(onClick = { vm.discard(doc) }, enabled = doc.dirty && canEditDocument) { Text("Отменить", fontSize = 11.sp) }
            IconButton(onClick = { vm.save() }, enabled = doc.dirty && canEditDocument) { Icon(Icons.Outlined.Save, "Сохранить файл", tint = if (doc.dirty) Green else Muted) }
        }
        val scroll = rememberScrollState()
        Row(Modifier.weight(1f).fillMaxWidth().background(Night).padding(vertical = 16.dp)) {
            Text((1..doc.text.count { it == '\n' } + 1).joinToString("\n"), Modifier.widthIn(min = 42.dp).padding(horizontal = 10.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, color = Color(0xFF63766A))
            key(doc.path.value) {
                var selection by remember(doc.path.value) { mutableStateOf(TextRange.Zero) }
                BasicTextField(value = TextFieldValue(doc.text, TextRange(selection.start.coerceIn(0, doc.text.length), selection.end.coerceIn(0, doc.text.length))), onValueChange = { doc.text = it.text; selection = it.selection }, enabled = canEditDocument, textStyle = TextStyle(color = Color(0xFFD8E4D9), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF94CD9E)), visualTransformation = remember(doc.readOnly) { if (doc.readOnly) VisualTransformation.None else CodeHighlight() }, modifier = Modifier.weight(1f).fillMaxHeight().testTag("editor-input").verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(end = 20.dp))
            }
        }
        Row(Modifier.fillMaxWidth().testTag("editor-extra-keys").horizontalScroll(rememberScrollState()).background(Color(0xFF233129)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("    ", "{", "}", "(", ")", "[", "]", ";", "=", "<", ">", "/", "\"", "'").forEach { symbol ->
                TextButton(onClick = {
                    doc.text = doc.text + symbol
                }, contentPadding = PaddingValues(8.dp), modifier = Modifier.defaultMinSize(minWidth = 32.dp), enabled = canEditDocument) { Text(if (symbol.isBlank()) "TAB" else symbol, color = Color(0xFFBFDBC4), fontFamily = FontFamily.Monospace) }
            }
        }
        Row(Modifier.fillMaxWidth().background(Soft).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(doc.path.name.substringAfterLast('.', "text").uppercase(), fontSize = 10.sp, color = Green)
            Text(doc.previewInfo ?: "UTF-8 · ${doc.text.lines().size} строк · ${if (doc.dirty) "изменён" else "сохранён"}", fontSize = 10.sp, color = Green)
        }
    }
}

private class CodeHighlight : VisualTransformation {
    private val tokens = Regex("(?m)(//[^\\n]*|#[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'[^'\\n]*'|\\b(?:package|import|fun|val|var|class|return|if|else|for|while|const|let|function|def|from|true|false|null|public|private|suspend|override)\\b|\\b\\d+\\b)")
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        tokens.findAll(text.text).take(20000).forEach { match ->
            val value = match.value
            val color = when { value.startsWith("//") || value.startsWith("#") -> Color(0xFF789382); value.startsWith("\"") || value.startsWith("'") -> Color(0xFFA9D793); value.first().isDigit() -> Color(0xFFE9B37E); else -> Color(0xFFC7A3E8) }
            builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AgentScreen(vm: IdeViewModel) {
    var prompt by vm::chatDraft
    val attachmentUris = vm.chatAttachments
    var historyOpen by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { picked ->
        attachmentUris.clear()
        attachmentUris.addAll(picked.take(com.notcode.mobile.chat.AttachmentClassifier.MAX_ATTACHMENT_FILES))
    }
    val attachmentKeys = attachmentUris.map { it.toString() }
    val attachmentNames = remember(attachmentKeys) { attachmentUris.map { uri -> vm.attachmentLabel(uri) } }
    val profiles by vm.profiles.collectAsState()
    val active by vm.activeProfileId.collectAsState()
    var modelMenu by remember { mutableStateOf(false) }
    var expandedModelProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    val chatProfiles = profiles.filter { it.usage != ModelUsage.IMAGE }
    val chatProviderGroups = remember(chatProfiles) {
        chatProfiles.withIndex().groupBy { (_, profile) ->
            profile.providerName.ifBlank { profile.baseUrl.ifBlank { "Свой провайдер" } }
        }
    }
    val state = rememberLazyListState()
    val projectFiles = vm.projectFiles.toList()
    val visibleChat = vm.chat.filter { it.role in setOf("Вы", "Агент", "Инструмент", "Подагент", "Система") && it.text.isNotBlank() }
    val timeline = buildChatTimeline(visibleChat)
    val chatRevision = vm.chat.map { it.role to it.text }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(chatRevision, imeVisible) {
        if (timeline.isNotEmpty()) state.scrollToItem(timeline.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("agent-chat")) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Green)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("Ваш помощник", fontWeight = FontWeight.Bold)
                Box {
                    TextButton(onClick = { modelMenu = true }, enabled = !vm.agentRunning, modifier = Modifier.testTag("assistant-model-picker")) {
                        Text(profiles.firstOrNull { it.id == active }?.title ?: "Выбрать модель", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false },
                        modifier = Modifier.widthIn(min = 250.dp, max = 340.dp).heightIn(max = 360.dp),
                    ) {
                        chatProviderGroups.forEach { (provider, indexedProfiles) ->
                            val expanded = provider in expandedModelProviders
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(provider, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(indexedProfiles.size.toString(), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                                    }
                                },
                                leadingIcon = { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                                onClick = {
                                    expandedModelProviders = if (expanded) expandedModelProviders - provider else expandedModelProviders + provider
                                },
                            )
                            if (expanded) indexedProfiles.forEach { (_, profile) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (profile.displayName.isNotBlank()) Text(profile.modelId, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    leadingIcon = { if (profile.id == active) Icon(Icons.Outlined.Check, null) },
                                    onClick = { vm.selectProfile(profile.id); modelMenu = false },
                                )
                            }
                        }
                        DropdownMenuItem(text = { Text("Настроить модели") }, onClick = { modelMenu = false; vm.tab = 3 })
                    }
                }
            }
            IconButton(onClick = { historyOpen = true }) { Icon(Icons.Outlined.History, "История чатов") }
            IconButton(onClick = { vm.clearChat() }, enabled = !vm.agentRunning) { Icon(Icons.Outlined.DeleteOutline, "Очистить диалог") }
        }
        if (vm.chat.isEmpty()) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Не чат рядом с пустотой.\nРабота с вашим проектом.", fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            Text("Агент читает файлы, ищет по проекту и предлагает изменения. Подтверждения включены по умолчанию; доверие можно включить только для текущего проекта.", Modifier.padding(top = 12.dp), color = Muted)
            listOf("Объясни структуру проекта", "Найди возможные ошибки", "Помоги написать тесты").forEach { suggestion -> OutlinedButton(onClick = { prompt = suggestion }, modifier = Modifier.padding(top = 8.dp)) { Text(suggestion, fontSize = 12.sp) } }
        } else LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(timeline, key = { it.id }) { entry ->
                when (entry) {
                    is ChatTimelineEntry.Message -> ChatMessageBubble(entry.item, vm, projectFiles)
                    is ChatTimelineEntry.Actions -> {
                        var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.testTag("chat-actions:${entry.id}").semantics {
                                    stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                                }
                            ) {
                                Icon(Icons.Outlined.Build, null, Modifier.size(16.dp))
                                Text(actionCountLabel(entry.items.size), Modifier.padding(horizontal = 8.dp))
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Свернуть действия" else "Развернуть действия")
                            }
                            if (expanded) entry.items.forEach { item ->
                                key(item.id) { ChatMessageBubble(item, vm, projectFiles) }
                            }
                        }
                    }
                }
            }
        }
        if (vm.agentRunning) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Агент работает", Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp); TextButton(onClick = { vm.stopAgent() }) { Text("Стоп") } }
        Column(Modifier.fillMaxWidth()) {
            if (attachmentUris.isNotEmpty()) Text(
                attachmentUris.zip(attachmentNames).joinToString(", ") { (uri, name) -> name.ifBlank { uri.lastPathSegment ?: "файл" } }.take(180),
                Modifier.padding(horizontal = 16.dp),
                color = Muted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { filePicker.launch("*/*") }, enabled = !vm.agentRunning && attachmentUris.size < com.notcode.mobile.chat.AttachmentClassifier.MAX_ATTACHMENT_FILES) { Icon(Icons.Outlined.AttachFile, "Прикрепить файл", tint = Green) }
            OutlinedTextField(prompt, { prompt = it }, placeholder = { Text("Что сделаем в проекте?", fontSize = 13.sp) }, modifier = Modifier.weight(1f).testTag("chat-input"), maxLines = 5, shape = RoundedCornerShape(18.dp))
            IconButton(onClick = { if (vm.send(prompt, attachmentUris.toList())) { prompt = ""; attachmentUris.clear() } }, enabled = (prompt.isNotBlank() || attachmentUris.isNotEmpty()) && !vm.agentRunning, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).background(Soft, RoundedCornerShape(16.dp))) { Icon(Icons.Outlined.ArrowUpward, "Отправить", tint = Green) }
            }
        }
        if (historyOpen) {
            val dateFormat = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }
            AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("История чатов") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.newChat(); historyOpen = false }, enabled = !vm.agentRunning) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("Новый чат", Modifier.padding(start = 6.dp)) }
                    if (vm.conversations.isEmpty()) Text("Пока пусто — отправьте первое сообщение.", color = Muted, fontSize = 12.sp)
                    vm.conversations.forEach { conversation ->
                        ListItem(
                            headlineContent = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (conversation.id == vm.activeConversationId) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text(dateFormat.format(java.util.Date(conversation.updatedAt)), fontSize = 11.sp, color = Muted) },
                            trailingContent = { IconButton(onClick = { vm.deleteConversation(conversation.id) }, enabled = !vm.agentRunning) { Icon(Icons.Outlined.DeleteOutline, "Удалить чат") } },
                            modifier = Modifier.clickable(enabled = !vm.agentRunning) { vm.openConversation(conversation.id); historyOpen = false }
                        )
                    }
                }
            }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("Закрыть") } })
        }
    }
}

@Composable
private fun ChatMessageBubble(item: ChatItem, vm: IdeViewModel, projectFiles: List<ProjectPath>) {
    val clipboard = LocalClipboardManager.current
    val tool = item.role == "Инструмент"
    val actionPath = item.filePath?.takeIf { path -> projectFiles.any { it.value == path } }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (item.role == "Вы") Alignment.CenterEnd else Alignment.CenterStart) {
        val bubbleWidth = minOf(maxWidth * 0.86f, 480.dp)
        Surface(
            color = if (item.role == "Вы") Soft else MaterialTheme.colorScheme.primary.copy(alpha = 0.065f).compositeOver(MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.widthIn(max = bubbleWidth).testTag("message-bubble:${item.id}").then(if (tool && actionPath != null) Modifier.clickable { vm.openFile(ProjectPath.parse(actionPath)) } else Modifier)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tool) Icons.Outlined.Build else if (item.role == "Вы") Icons.Outlined.PersonOutline else Icons.Outlined.AutoAwesome, null, Modifier.size(15.dp), tint = Green)
                    Text(item.role.uppercase(), Modifier.padding(start = 7.dp).weight(1f, fill = false), color = Green, fontSize = 10.sp, letterSpacing = 1.sp)
                    if (tool && item.successful != null) Icon(if (item.successful == true) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, if (item.successful == true) "Выполнено" else "Не выполнено", Modifier.size(16.dp), tint = if (item.successful == true) Green else MaterialTheme.colorScheme.error)
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LinkedMessageText(item.text, projectFiles, Modifier.padding(top = 10.dp), openUrl = { vm.openPreviewUrl(it) }, open = { path ->
                        if (vm.projectFiles.any { it == path }) vm.openFile(path)
                    })
                }
                if (tool && actionPath != null) TextButton(onClick = {
                    if (vm.projectFiles.any { it.value == actionPath }) vm.openFile(ProjectPath.parse(actionPath))
                }, contentPadding = PaddingValues(vertical = 4.dp)) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                    Text(actionPath, Modifier.padding(start = 6.dp), fontSize = 12.sp)
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(item.text)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Копировать сообщение", Modifier.size(16.dp), tint = Muted)
                }
            }
        }
    }
}

@Composable private fun TerminalScreen(vm: IdeViewModel) {
    var git by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val canEdit = !vm.busy && !vm.agentRunning
    val runtime by vm.runtimeState.collectAsState()
    val shellReady = when (vm.shellMode) {
        "ubuntu", "cmd" -> runtime.ubuntuReady && runtime.unsupportedReason == null
        "powershell" -> runtime.ubuntuReady && runtime.powershellReady && runtime.unsupportedReason == null
        else -> false
    }
    val canRunShell = canEdit && !runtime.installing && shellReady
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { git = false }) { Text("Команды", color = if (!git) Green else Muted) }
            TextButton(onClick = { git = true }, enabled = !vm.interactiveRunning) { Text("Git", color = if (git) Green else Muted) }
            Spacer(Modifier.weight(1f))
            if (!git && vm.interactiveRunning) IconButton(onClick = { vm.closeInteractiveTerminal() }) { Icon(Icons.Outlined.StopCircle, "Закрыть сессию терминала") }
        }
        if (!git) Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ubuntu" to "Ubuntu", "powershell" to "PowerShell", "cmd" to "CMD").forEach { (mode, label) ->
                    FilterChip(
                        selected = vm.shellMode == mode,
                        onClick = { vm.selectShell(mode) },
                        enabled = !runtime.installing && canEdit && !vm.interactiveRunning,
                        label = { Text(label) },
                        modifier = Modifier.testTag("shell:$mode")
                    )
                }
            }
        }
        if (git) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            listOf("init" to "Создать", "status" to "Статус", "diff" to "Diff", "log" to "История").forEach { (action, title) -> TextButton(onClick = { vm.git(action) }, enabled = canEdit) { Text(title) } }
        }
        if (!git && !shellReady) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                val name = when (vm.shellMode) { "powershell" -> "PowerShell"; "cmd" -> "CMD"; else -> "Ubuntu" }
                Icon(Icons.Outlined.Download, null, Modifier.size(40.dp), tint = Green)
                Text("$name — не установлено", Modifier.padding(vertical = 16.dp).testTag("shell-not-installed"), style = MaterialTheme.typography.titleLarge)
                runtime.unsupportedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (runtime.installing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(runtime.progress, Modifier.padding(vertical = 12.dp), color = Muted)
                    TextButton(onClick = { vm.cancelRuntimeInstall() }) { Text("Отменить установку") }
                } else {
                    val needsUbuntu = !runtime.ubuntuReady
                    if (vm.shellMode == "powershell" && needsUbuntu) Text("PowerShell работает внутри Ubuntu. Сначала скачайте Ubuntu, затем PowerShell.", color = Muted)
                    Button(onClick = { if (needsUbuntu) vm.installUbuntu() else vm.installPowerShell() }, enabled = canEdit && runtime.unsupportedReason == null, modifier = Modifier.padding(top = 16.dp).testTag("shell-download")) {
                        Text(if (needsUbuntu) "Скачать Ubuntu" else "Скачать PowerShell")
                    }
                }
            }
        } else if (!git) {
            InteractiveTerminalContent(vm, Modifier.weight(1f), canRunShell)
        } else {
        Box(Modifier.weight(1f).fillMaxWidth().background(Night).verticalScroll(rememberScrollState()).padding(20.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer { Text(vm.gitOutput, color = Color(0xFFBDD4C1), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp) }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Сообщение коммита") }, modifier = Modifier.weight(1f).testTag("terminal-input"), singleLine = true, enabled = canEdit)
            IconButton(onClick = { vm.git("commit", input); input = "" }, enabled = input.isNotBlank() && canEdit) { Icon(Icons.Outlined.Check, "Создать коммит") }
        }
        }
    }
}

@Composable
private fun InteractiveTerminalContent(vm: IdeViewModel, modifier: Modifier, canRunShell: Boolean) {
    Column(modifier.fillMaxWidth()) {
            val session = vm.interactiveSession
            if (session == null || !vm.interactiveRunning) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Интерактивный ${when (vm.shellMode) { "powershell" -> "PowerShell"; "cmd" -> "CMD"; else -> "Ubuntu" }}", style = MaterialTheme.typography.titleLarge)
                    Text("Постоянная сессия, живой вывод и ввод ответов. После подтверждения открытия сессии вводимые команды выполняются без отдельных подтверждений: они могут менять файлы и обращаться к сети. PRoot не является защитной песочницей. При сворачивании приложения сессия продолжает работать в фоне.", Modifier.padding(vertical = 16.dp), color = Muted)
                    Button(onClick = { vm.startInteractiveTerminal() }, enabled = canRunShell, modifier = Modifier.testTag("terminal-open")) { Text("Открыть терминал") }
                }
            } else {
                if (vm.terminalUrls.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    vm.terminalUrls.toList().forEach { url -> TextButton(onClick = { vm.openPreviewUrl(url) }) { Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(16.dp)); Text(url, Modifier.padding(start = 6.dp), maxLines = 1, fontSize = 11.sp) } }
                }
                var terminalView by remember { mutableStateOf<com.notcode.mobile.terminal.PtyTerminalView?>(null) }
                var modifierTick by remember { mutableIntStateOf(0) }
                androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                    com.notcode.mobile.terminal.PtyTerminalView(context).apply {
                        bind(session)
                        setOnModifierChangedListener { modifierTick++ }
                        terminalView = this
                    }
                }, update = { it.bind(session) }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("interactive-terminal"))
                val ctrlOn = modifierTick >= 0 && terminalView?.isControlActive == true
                val altOn = modifierTick >= 0 && terminalView?.isAltActive == true
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { vm.interruptTerminal() }) { Text("Ctrl+C", fontSize = 12.sp) }
                    TextButton(onClick = { terminalView?.toggleControl() }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(containerColor = if (ctrlOn) Green.copy(alpha = 0.25f) else Color.Transparent)) { Text("CTRL", fontSize = 12.sp) }
                    TextButton(onClick = { terminalView?.toggleAlt() }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(containerColor = if (altOn) Green.copy(alpha = 0.25f) else Color.Transparent)) { Text("ALT", fontSize = 12.sp) }
                    listOf("ESC" to "\u001b", "TAB" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "|" to "|", "/" to "/", "-" to "-", "_" to "_", "~" to "~", "=" to "=", "⏎" to "\r").forEach { (label, value) ->
                        TextButton(onClick = { vm.writeTerminal(value) }) { Text(label, fontSize = 12.sp) }
                    }
                    TextButton(onClick = { terminalView?.pasteFromClipboard() }) { Text("Вставить", fontSize = 12.sp) }
                }
            }
    }
}

@Composable
private fun RuntimePanel(vm: IdeViewModel, runtime: RuntimeState) {
    val canInstall = !runtime.installing && runtime.unsupportedReason == null &&
        !vm.busy && !vm.agentRunning && vm.approval == null
    OutlinedCard(Modifier.fillMaxWidth().testTag("runtime-panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Среды выполнения", style = MaterialTheme.typography.titleMedium)
            Text("Ubuntu: ${if (runtime.ubuntuReady) "установлена" else "не установлена"} · PowerShell: ${if (runtime.powershellReady) "установлен" else "не установлен"}",
                color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("runtime-status"))
            runtime.unsupportedReason?.let { reason ->
                Text("Среды недоступны на этом устройстве: $reason", color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp, modifier = Modifier.testTag("runtime-unsupported"))
            }
            Text("Образ и зависимости скачиваются отдельно; требуется не менее 1,5 ГБ свободного места.", color = Muted, fontSize = 12.sp)
            Text("Установка — только по вашей кнопке, не по запросу модели. Перед обращением к сети требуется подтверждение приложения. Загрузка использует Wi-Fi или мобильный интернет; возможна плата за трафик.", color = Muted, fontSize = 12.sp)
            Text("PowerShell работает в Ubuntu: это настоящий PowerShell для Linux с привычными командами (dir, cd, copy, del, cls), без Windows-модулей. CMD — эмуляция команд cmd.exe поверх Ubuntu. Используется интерактивный PTY-терминал с постоянной сессией, Ctrl+C и клавишами CTRL/ALT. Systemd не поддерживается.", color = Muted, fontSize = 12.sp)
            if (runtime.installing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("runtime-installing"))
            }
            if (runtime.progress.isNotBlank()) {
                Text(runtime.progress, color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("runtime-progress"))
            }
            if (!runtime.ubuntuReady) {
                Button(onClick = { vm.installUbuntu() }, enabled = canInstall,
                    modifier = Modifier.testTag("runtime-install-ubuntu")) { Text("Установить Ubuntu") }
            }
            if (!runtime.powershellReady) {
                OutlinedButton(onClick = { vm.installPowerShell() }, enabled = runtime.ubuntuReady && canInstall,
                    modifier = Modifier.testTag("runtime-install-powershell")) { Text("Установить PowerShell") }
                if (!runtime.ubuntuReady) Text("Сначала установите Ubuntu.", color = Muted, fontSize = 12.sp)
            }
            if (runtime.installing) {
                TextButton(onClick = { vm.cancelRuntimeInstall() }, modifier = Modifier.testTag("runtime-cancel")) { Text("Отменить установку") }
            }
        }
    }
}

@Composable private fun SettingsScreen(vm: IdeViewModel) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val profiles by vm.profiles.collectAsState()
    val active by vm.activeProfileId.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val runtime by vm.runtimeState.collectAsState()
    var edit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ваш ИИ. Ваши правила.", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { uriHandler.openUri("https://t.me/NotCodeAI") }, modifier = Modifier.testTag("telegram-channel")) {
            Icon(Icons.Outlined.Send, null, Modifier.size(18.dp)); Text("Telegram-канал NotCode", Modifier.padding(start = 8.dp))
        }
        Text("Тема оформления", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (mode, label) ->
                FilterChip(selected = themeMode == mode, onClick = { vm.setThemeMode(mode) }, label = { Text(label) }, modifier = Modifier.testTag("theme:$mode"))
            }
        }
        HorizontalDivider()
        RuntimePanel(vm, runtime)
        HorizontalDivider()
        Text("Модели", style = MaterialTheme.typography.titleMedium)
        Text("Добавьте провайдера один раз и укажите его модели списком. Для них будут общими адрес, API-ключ и настройки подключения; в помощнике выбирается конкретная модель.", color = Muted, fontSize = 13.sp)
        val settingsProviderGroups = remember(profiles) {
            profiles.withIndex().groupBy { (_, profile) ->
                "${profile.usage.name}:${profile.providerName.ifBlank { profile.baseUrl.ifBlank { "Свой провайдер" } }}"
            }
        }
        settingsProviderGroups.forEach { (groupKey, indexedProfiles) ->
            val firstProfile = indexedProfiles.first().value
            val imageProfile = firstProfile.usage == ModelUsage.IMAGE
            val providerTitle = firstProfile.providerName.ifBlank { firstProfile.baseUrl.ifBlank { "Свой провайдер" } }
            val groupTitle = if (imageProfile) "$providerTitle · генерация изображений" else providerTitle
            var expanded by rememberSaveable(groupKey) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().semantics {
                    stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (imageProfile) Icons.Outlined.Image else Icons.Outlined.Dns, null, tint = Green)
                        Text(groupTitle, Modifier.weight(1f).padding(horizontal = 10.dp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(indexedProfiles.size.toString(), color = Muted, fontSize = 12.sp)
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Свернуть модели" else "Развернуть модели", Modifier.padding(start = 6.dp), tint = Muted)
                    }
                }
                if (expanded) indexedProfiles.forEach { (index, profile) ->
                    OutlinedCard(
                        onClick = { if (imageProfile) { editing = profile; edit = true } else vm.selectProfile(profile.id) },
                        enabled = !vm.agentRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (imageProfile) Icons.Outlined.Image else if (profile.id == active) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = Green)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(profile.modelId, fontWeight = FontWeight.Bold)
                                    if (profile.displayName.isNotBlank()) Text(profile.displayName, fontSize = 12.sp)
                                }
                                IconButton(onClick = { editing = profile; edit = true }, enabled = !vm.agentRunning) { Icon(Icons.Outlined.Edit, "Редактировать профиль") }
                                IconButton(onClick = { vm.deleteProfile(profile) }, enabled = !vm.agentRunning) { Icon(Icons.Outlined.DeleteOutline, "Удалить профиль и ключ") }
                            }
                            if (imageProfile) Text("Генерация изображений · агент вызывает её через generate_image", fontSize = 12.sp, color = Green)
                            Text(profile.baseUrl, fontSize = 11.sp, color = Muted)
                            Row {
                                TextButton(onClick = { vm.moveProfile(profile.id, -1) }, enabled = index > 0 && !vm.agentRunning) { Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(16.dp)); Text("Выше") }
                                TextButton(onClick = { vm.moveProfile(profile.id, 1) }, enabled = index < profiles.lastIndex && !vm.agentRunning) { Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp)); Text("Ниже") }
                            }
                        }
                    }
                }
            }
        }
        Button(onClick = { editing = null; edit = true }, enabled = !vm.agentRunning) { Icon(Icons.Outlined.Add, null); Text("Добавить модель", Modifier.padding(start = 8.dp)) }
        OutlinedButton(onClick = { vm.testProfile() }, enabled = active != null && !vm.agentRunning) { Text("Проверить подключение") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingNote(Icons.Outlined.Key, "Ключ только на устройстве", "AES-GCM и Android Keystore. Ключ не входит в историю диалога и не доступен инструментам агента.")
        SettingNote(Icons.Outlined.FolderOpen, "Только выбранная папка", "SAF не даёт доступ ко всему телефону. Пока поддерживаются текстовые UTF-8-проекты до 2000 файлов и 32 МиБ, без бинарных ресурсов. Редактор работает с внутренней копией; синхронизация экспортирует изменения, проверяя конфликты.")
        SettingNote(Icons.Outlined.Shield, "Доверие только текущему проекту", "По умолчанию операции требуют подтверждения. Флажок в диалоге отключает повторные запросы только для открытого проекта. Команды выполняются с правами приложения, а не в изолированной песочнице.")
        Text(if (vm.trustEnabled) "Для текущего проекта включено доверие" else "Подтверждения включены", color = if (vm.trustEnabled) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp)
        OutlinedButton(onClick = { vm.resetProjectTrust() }, enabled = vm.project != null && vm.trustEnabled) { Text("Снова спрашивать в этом проекте") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        UpdatePanel(vm)
        Text("NotCode ${BuildConfig.VERSION_NAME} · Android 12+\nРедактор, модели, read-only подагенты, Git и команды Ubuntu / PowerShell. Дополнительные среды устанавливаются пользователем.\nКлонирование и создание GitHub-репозиториев, поиск в интернете доступны агенту с подтверждением.", fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
    }
    if (edit) {
        val scope = rememberCoroutineScope()
        var provider by remember(editing) { mutableStateOf(editing?.providerName.orEmpty()) }
        var presetMenu by remember { mutableStateOf(false) }
        var note by remember(editing) { mutableStateOf(ProviderPresets.items.firstOrNull { it.name == editing?.providerName }?.note.orEmpty()) }
        var name by remember(editing) { mutableStateOf(editing?.displayName ?: "") }
        var modelTitle by remember(editing) { mutableStateOf(editing?.title ?: "") }
        var url by remember(editing) { mutableStateOf(editing?.baseUrl ?: "") }
        var model by remember(editing) { mutableStateOf(editing?.modelId ?: "") }
        var key by remember(editing) { mutableStateOf("") }
        var fetchingModels by remember(editing) { mutableStateOf(false) }
        var modelFetchMessage by remember(editing) { mutableStateOf("") }
        var noAuth by remember(editing) { mutableStateOf(editing?.authMode == AuthMode.NONE) }
        var tools by remember(editing) { mutableStateOf(editing?.toolsMode != CapabilityMode.DISABLED) }
        var streaming by remember(editing) { mutableStateOf(editing?.streamingMode != CapabilityMode.DISABLED) }
        var imageMode by remember(editing) { mutableStateOf(editing?.usage == com.notcode.mobile.model.ModelUsage.IMAGE) }
        AlertDialog(onDismissRequest = { edit = false; key = "" }, title = { Text(if (editing == null) "Новая модель" else "Настройки модели") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth().testTag("provider-preset-picker")) { Text(provider.ifBlank { "Свой провайдер" }); Icon(Icons.Outlined.ArrowDropDown, null) }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        ProviderPresets.items.forEach { preset ->
                            DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                                provider = preset.name; url = preset.baseUrl; model = preset.suggestedModel; note = preset.note
                                key = ""; noAuth = false; presetMenu = false
                            })
                        }
                    }
                }
                Text("Нет ключа или не хочешь платить? Заходи в Telegram-канал — там способы доступа к топовым моделям и туториалы по ИИ.", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { uriHandler.openUri("https://t.me/NotCodeAI") }) { Text("Подписаться", color = Green) }
                if (note.isNotBlank()) Text(note, color = Muted, fontSize = 12.sp)
                OutlinedTextField(model, { model = it }, label = { Text("ID моделей (по одной в строке или через запятую)") }, minLines = if (editing == null) 3 else 1, maxLines = 5)
                OutlinedTextField(modelTitle, { modelTitle = it }, label = { Text("Красивое название (необязательно)") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Base URL с /v1, если требуется") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text(if (editing == null) "API-ключ (если требуется)" else "Новый ключ (пусто — сохранить)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !noAuth)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            fetchingModels = true
                            modelFetchMessage = "Запрашиваю GET /models…"
                            try {
                                val ids = vm.fetchModels(url, key, noAuth)
                                model = ids.joinToString("\n")
                                modelFetchMessage = "Загружено моделей: ${ids.size}. Список можно отредактировать."
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                val reason = error.message?.take(160)?.takeIf { it.isNotBlank() } ?: "ошибка сети"
                                modelFetchMessage = "Не удалось загрузить модели: $reason"
                            } finally {
                                fetchingModels = false
                            }
                        }
                    },
                    enabled = !fetchingModels && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (fetchingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Fetch моделей", Modifier.padding(start = 8.dp))
                }
                Text("Автозагрузка может не сработать: провайдер может не поддерживать /models или возвращать список в другом формате. Тогда укажите ID моделей вручную.", fontSize = 11.sp, color = Muted)
                if (modelFetchMessage.isNotBlank()) Text(modelFetchMessage, fontSize = 12.sp, color = if (modelFetchMessage.startsWith("Не удалось")) MaterialTheme.colorScheme.error else Muted)
                Toggle("Без авторизации", noAuth) { noAuth = it }
                Toggle("Модель для генерации изображений", imageMode) { imageMode = it }
                if (imageMode) {
                    Text("Использует POST /images/generations. Такая модель не выбирается как основная, но основной агент может вызывать generate_image. Сохранение картинок всегда спрашивает вас.", fontSize = 11.sp, color = Muted)
                }
                Toggle("Инструменты агента", tools) { tools = it }
                Toggle("Потоковый ответ", streaming) { streaming = it }
                Text("Используйте HTTPS. Отключите потоковый ответ или инструменты, если модель их не поддерживает.", fontSize = 11.sp, color = Muted)
            }
        }, confirmButton = { TextButton(onClick = { vm.saveProfile(modelTitle, url, model, key, noAuth, tools, streaming, editing?.id, providerName = provider, imageMode = imageMode); key = ""; edit = false }, enabled = url.isNotBlank() && model.isNotBlank() && (noAuth || key.isNotBlank() || editing != null)) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { edit = false; key = "" }) { Text("Отмена") } })
    }
}
@Composable private fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 12.sp); Switch(checked, change) } }
@Composable private fun SettingNote(icon: ImageVector, title: String, description: String) {
    Row(Modifier.padding(vertical = 6.dp)) { Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp)); Column(Modifier.padding(start = 12.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(description, Modifier.padding(top = 4.dp), color = Muted, fontSize = 12.sp, lineHeight = 19.sp) } }
}
@Composable private fun UpdatePanel(vm: IdeViewModel) {
    val update = vm.updateStatus
    val repository by vm.updateRepository.collectAsState()
    val autoCheck by vm.autoUpdateEnabled.collectAsState()
    var repositoryDraft by remember(repository) { mutableStateOf(repository) }
    Text("Обновления", style = MaterialTheme.typography.titleMedium)
    Toggle("Проверять автоматически", autoCheck) { vm.setAutoUpdateEnabled(it) }
    Text("Раз в 6 часов приложение смотрит релизы GitHub. Скачивание и установка — только по вашему нажатию.", fontSize = 11.sp, color = Muted)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(repositoryDraft, { repositoryDraft = it }, label = { Text("Репозиторий релизов (owner/repo)") }, singleLine = true, modifier = Modifier.weight(1f).testTag("update-repository"))
        TextButton(onClick = { vm.setUpdateRepository(repositoryDraft) }, enabled = repositoryDraft.isNotBlank() && repositoryDraft != repository) { Text("Сохранить") }
    }
    OutlinedButton(onClick = { vm.checkForUpdates() }, enabled = update.phase != UpdatePhase.CHECKING, modifier = Modifier.fillMaxWidth().testTag("check-updates")) {
        if (update.phase == UpdatePhase.CHECKING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(if (update.phase == UpdatePhase.CHECKING) "Проверяю релизы…" else "Проверить обновления", Modifier.padding(start = 8.dp))
    }
    when (update.phase) {
        UpdatePhase.UP_TO_DATE -> Text("Установлена последняя версия (${update.version}).", fontSize = 12.sp, color = Muted)
        UpdatePhase.FAILED -> Text("Не получилось: ${update.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        UpdatePhase.AVAILABLE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Доступно ${update.version} · установлена ${vm.installedVersionName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (update.notes.isNotBlank()) Text(update.notes.take(600), fontSize = 11.sp, color = Muted, lineHeight = 17.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Button(onClick = { vm.downloadUpdate() }, modifier = Modifier.fillMaxWidth().testTag("download-update")) { Text("Скачать ${update.version}") }
        }
        UpdatePhase.DOWNLOADING -> {
            val percent = if (update.totalBytes > 0) (update.downloadedBytes * 100 / update.totalBytes).toFloat() / 100f else 0f
            LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
            val downloaded = update.downloadedBytes / (1024 * 1024)
            val total = if (update.totalBytes > 0) "${update.totalBytes / (1024 * 1024)} МиБ" else "неизвестно"
            Text("Загружено $downloaded МиБ из $total · ${vm.installedVersionName} → ${update.version}", fontSize = 12.sp, color = Muted)
        }
        UpdatePhase.READY -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.installUpdate() }, modifier = Modifier.fillMaxWidth().testTag("install-update")) { Text("Установить ${update.version}") }
            Text("Откроется системный установщик — подтвердите установку там. История чатов, модели и файлы проекта сохраняются.", fontSize = 11.sp, color = Muted)
        }
        else -> Unit
    }
}
