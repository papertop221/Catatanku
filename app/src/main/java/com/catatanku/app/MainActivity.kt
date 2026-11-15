package com.catatanku.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    val vm: AppViewModel by viewModels()

    /** Teks yang dibagikan dari aplikasi lain menjadi catatan baru. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        vm.saveDraft()
        vm.open(Note(title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty().take(120), body = text))
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            CatatankuTheme {
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
                when (vm.screen) {
                    Screen.List -> ListScreen(vm, snack)
                    Screen.Editor -> { BackHandler { vm.closeEditor() }; EditorScreen(vm, snack) }
                    Screen.Ask -> { BackHandler { vm.closeAsk() }; AskScreen(vm, snack) }
                    Screen.Settings -> { BackHandler { vm.go(Screen.List) }; SettingsScreen(vm, snack) }
                }
            }
        }
    }
}
