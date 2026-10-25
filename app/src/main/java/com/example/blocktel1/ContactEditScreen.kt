package com.example.blocktel1

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun ContactAvatar(
    photoUri: String?,
    name: String,
    size: Dp = 44.dp
) {
    if (!photoUri.isNullOrBlank()) {
        AsyncImage(
            model = photoUri,
            contentDescription = name,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        val initials = name.trim()
            .split(Regex("\\s+"))
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")

        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials.ifBlank { "?" },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = (size.value / 2.5f).sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditScreen(
    draft: ContactDraft,
    onSave: (ContactDraft) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    var firstName by remember { mutableStateOf(draft.firstName) }
    var lastName by remember { mutableStateOf(draft.lastName) }
    var middleName by remember { mutableStateOf(draft.middleName) }
    var displayName by remember { mutableStateOf(draft.displayName) }
    var phones by remember { mutableStateOf(draft.phones.ifEmpty { listOf("") }) }
    var photoUri by remember { mutableStateOf(draft.photoUri) }

    // true, если пользователь вручную правил displayName
    var displayNameManual by remember { mutableStateOf(draft.displayName.isNotBlank()) }

    fun rebuildDisplayIfAuto() {
        if (!displayNameManual) {
            displayName = listOf(lastName, firstName, middleName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val cached = ContactPhotoStore.copyToCache(context, uri)
            if (cached != null) photoUri = cached.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (draft.id == null) "Новый контакт" else "Редактировать контакт")
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Отмена")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val cleanedPhones = phones
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                            val finalDisplay = displayName.ifBlank {
                                listOf(lastName, firstName, middleName)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                            }
                            onSave(
                                draft.copy(
                                    firstName = firstName.trim(),
                                    lastName = lastName.trim(),
                                    middleName = middleName.trim(),
                                    displayName = finalDisplay.trim(),
                                    phones = cleanedPhones,
                                    photoUri = photoUri
                                )
                            )
                        }
                    ) {
                        Text("Сохранить")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                ContactAvatar(
                    photoUri = photoUri,
                    name = displayName.ifBlank { firstName },
                    size = 120.dp
                )
                Row {
                    IconButton(
                        onClick = {
                            pickPhotoLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Выбрать фото",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (photoUri != null) {
                        IconButton(onClick = { photoUri = null }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить фото",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it; rebuildDisplayIfAuto() },
                label = { Text("Фамилия") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it; rebuildDisplayIfAuto() },
                label = { Text("Имя") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = middleName,
                onValueChange = { middleName = it; rebuildDisplayIfAuto() },
                label = { Text("Отчество") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    displayNameManual = it.isNotBlank()
                },
                label = { Text("Отображаемое имя (FN)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Телефоны",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            phones.forEachIndexed { index, phone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { newValue ->
                            phones = phones.toMutableList().also { it[index] = newValue }
                        },
                        label = { Text("Телефон ${index + 1}") },
                        modifier = Modifier.weight(1f)
                    )
                    if (phones.size > 1) {
                        IconButton(
                            onClick = {
                                phones = phones.filterIndexed { i, _ -> i != index }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить телефон")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            TextButton(onClick = { phones = phones + "" }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Добавить телефон")
            }
        }
    }
}