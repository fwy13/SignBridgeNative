package com.fwy13.signbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.fwy13.signbridge.BridgeViewModel

@Composable
fun RegionScreen(vm: BridgeViewModel) {
    CenteredEntryScreen {
        Brand(centered = true)
        Text(
            bilingual("Bắt đầu trải nghiệm", "Get started"),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            bilingual(
                "Chọn ngôn ngữ ký hiệu bạn muốn sử dụng",
                "Choose the sign language you want to use"
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        listOf(
            Triple("hoa_de", "Hòa Đê", "Sóc Trăng, Việt Nam"),
            Triple("vsl", "VSL", "Ngôn ngữ ký hiệu chuẩn Việt Nam")
        ).forEach { (value, label, detail) ->
            OutlinedCard(
                onClick = { if (!vm.busy) vm.region(value) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleLarge
                    ); Text(detail)
                }
            }
        }
    }
}

@Composable
fun PasswordField(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = {
                visible = !visible
            }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    label
                )
            }
        })
}

@Composable
fun AuthScreen(vm: BridgeViewModel) {
    var signup by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // Passwords stay in memory only, not in saved-instance-state bundles.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    CenteredEntryScreen {
        Brand(centered = true)
        Text(
            tr(if (signup) "auth.registerSubtitle" else "auth.loginSubtitle"),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        if (signup) OutlinedTextField(
            name,
            { name = it },
            label = { Text(tr("auth.fullName")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy
        )
        OutlinedTextField(
            email,
            { email = it },
            label = { Text(tr("auth.email")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy
        )
        PasswordField(password, { password = it }, tr("auth.password"), !vm.busy)
        if (signup) PasswordField(
            confirmation,
            { confirmation = it },
            tr("auth.confirmPassword"),
            !vm.busy
        )
        Button(
            onClick = {
                if (signup && password != confirmation) vm.error =
                    vm.texts("auth.passwordMismatch") else vm.action {
                    if (signup) {
                        vm.account.signUp(name.trim(), email.trim(), password); vm.notice =
                            vm.texts("auth.registerSuccess"); signup = false; password =
                            ""; confirmation = ""
                    } else {
                        vm.account.signIn(email.trim(), password); password = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy && email.isNotBlank() && password.isNotEmpty() && (!signup || (name.isNotBlank() && confirmation.isNotEmpty()))
        ) { Text(tr(if (signup) "auth.signUp" else "auth.signIn")) }
        TextButton(
            onClick = { signup = !signup; password = ""; confirmation = "" },
            enabled = !vm.busy
        ) { Text(tr(if (signup) "auth.haveAccount" else "auth.noAccount") + " " + tr(if (signup) "auth.signIn" else "auth.signUp")) }
    }
}

/** Centers compact forms while remaining scrollable on small screens and with the keyboard open. */
@Composable
private fun CenteredEntryScreen(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            content = content
        )
    }
}
