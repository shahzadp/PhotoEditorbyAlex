package com.alex.photoeditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alex.photoeditor.ui.EditorViewModel
import com.alex.photoeditor.ui.screens.EditorScreen
import com.alex.photoeditor.ui.theme.PhotoEditorTheme

class MainActivity : ComponentActivity() {

    /**
     * Set when the picker or another app hands us an image to edit.
     *
     * This must be Compose state, not a plain field: `LaunchedEffect` only
     * re-runs when its key is observed, so a non-observable field would leave
     * the editor stuck on the empty screen after a pick.
     */
    private var pendingUri by mutableStateOf<Uri?>(null)

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // OpenDocument grants a persistable read grant, so the image stays
            // readable across process death. PickVisualMedia does not, which is
            // why a picked photo could not be re-opened later.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingUri = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingUri = extractImageUri(intent)

        setContent {
            PhotoEditorTheme {
                val vm: EditorViewModel = viewModel()
                val context = LocalContext.current
                val shareUri by vm.shareRequest.collectAsStateWithLifecycle()

                LaunchedEffect(pendingUri) {
                    pendingUri?.let {
                        vm.openImage(it)
                        // Clear before the load finishes so a re-pick of the
                        // same photo still registers as a change.
                        pendingUri = null
                    }
                }

                LaunchedEffect(shareUri) {
                    val uri = shareUri ?: return@LaunchedEffect
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share edited photo"))
                    }
                    vm.consumeShareRequest()
                }

                EditorScreen(
                    vm = vm,
                    onPickImage = { pickImage.launch(arrayOf("image/*")) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractImageUri(intent)?.let { pendingUri = it }
    }

    /** Pulls an image out of a SEND, EDIT or VIEW intent. */
    private fun extractImageUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_EDIT, Intent.ACTION_VIEW -> intent.data
            else -> null
        }?.takeIf { it.scheme != null } ?: return null

        // An externally shared image arrives with a one-shot grant, so claim
        // the persistable flag too when the sender offered it. Without this the
        // read can be revoked before we finish copying.
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        return uri
    }
}
