package com.nbs.print

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import com.nbs.print.ipp.Destination
import com.nbs.print.layout.PageParse
import com.nbs.print.layout.Paper
import com.nbs.print.print.DocPrintAdapter
import com.nbs.print.ui.NbsPrintTheme
import com.nbs.print.ui.PrintActions
import com.nbs.print.ui.PrintScreen
import com.nbs.print.ui.PrintViewModel

class MainActivity : ComponentActivity() {
    private val vm: PrintViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.load(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on every device; the UI applies system-bar insets itself, so it works with
        // 3-button navigation, 2-button navigation and gesture navigation alike.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            NbsPrintTheme {
                val state by vm.state.collectAsState()
                PrintScreen(
                    state = state,
                    a = PrintActions(
                        onPick = { picker.launch(arrayOf("application/pdf", "image/*", "text/plain")) },
                        onPrint = ::printNow,
                        onPrinterSettings = ::openPrinterSettings,
                        onChange = vm::update,
                        onPage = vm::setPage,
                        onDismissError = vm::dismissError,
                        onPages = vm::updatePages,
                        onOpenPicker = vm::openPicker,
                        onClosePicker = vm::closePicker,
                        onSelectDestination = vm::selectDestination,
                        onRemoveSaved = vm::removeSaved,
                        onAddPrinter = vm::addPrinterByAddress,
                        onDirect = vm::updateDirect,
                        onRetryCaps = vm::retryCaps,
                        onCancelJob = vm::cancelJob,
                        onDismissJob = vm::dismissJob,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { vm.load(listOf(it)) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) vm.load(listOf(stream))
                else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { vm.load(emptyList(), it.toString()) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) vm.load(uris)
            }
        }
    }

    private fun printNow() {
        val state = vm.state.value
        if (state.destination is Destination.Network) {
            vm.printDirect()
            return
        }
        val doc = state.doc ?: return
        val pages = (state.selection as? PageParse.Valid)?.pages ?: return
        val o = state.options
        val portrait = mediaFor(o.paper)
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (o.landscape) portrait.asLandscape() else portrait.asPortrait())
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val jobName = doc.title
        getSystemService(PrintManager::class.java)
            .print(jobName, DocPrintAdapter(this, doc, o, jobName, pages), attrs)
    }

    private fun openPrinterSettings() {
        try {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Android Settings and search for \"Printing\".", Toast.LENGTH_LONG).show()
        }
    }

    // Use the framework's own constants so the print dialog recognises the paper size.
    private fun mediaFor(p: Paper): PrintAttributes.MediaSize = when (p) {
        Paper.LETTER -> PrintAttributes.MediaSize.NA_LETTER
        Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
        Paper.LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
        Paper.A5 -> PrintAttributes.MediaSize.ISO_A5
        Paper.A3 -> PrintAttributes.MediaSize.ISO_A3
        Paper.TABLOID -> PrintAttributes.MediaSize.NA_TABLOID
        Paper.PHOTO_4X6 -> PrintAttributes.MediaSize.NA_INDEX_4X6
        Paper.PHOTO_5X8 -> PrintAttributes.MediaSize.NA_INDEX_5X8
    }
}
