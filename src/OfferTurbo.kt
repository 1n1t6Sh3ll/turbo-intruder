package burp

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import java.util.ArrayList
import javax.swing.JMenuItem
import kotlin.jvm.optionals.getOrNull

const val SCRIPT_MARKER = "--- Script ---"

fun extractScriptFromNotes(notes: String?): String? {
    if (notes.isNullOrBlank()) return null
    val idx = notes.lastIndexOf(SCRIPT_MARKER)
    if (idx < 0) return null
    val script = notes.substring(idx + SCRIPT_MARKER.length).trim()
    return script.ifBlank { null }
}

class OfferTurboIntruder(): IContextMenuFactory {
    override fun createMenuItems(invocation: IContextMenuInvocation?): MutableList<JMenuItem> {
        val options = ArrayList<JMenuItem>()
        if (invocation != null && invocation.selectedMessages != null && invocation.selectedMessages[0] != null && invocation.selectedMessages[0].httpService != null) {
            val probeButton = JMenuItem("Send to turbo intruder")
            val bounds = invocation.selectionBounds ?: IntArray(0)
            probeButton.addActionListener(TurboIntruderFrame(invocation.selectedMessages[0], bounds, null, null, null))
            options.add(probeButton)
        }
        return options
    }
}

class BulkMenu(): ContextMenuItemsProvider {
    override fun provideMenuItems(event: ContextMenuEvent?): MutableList<Component> {
        if (event == null || event!!.selectedRequestResponses() === null || event!!.selectedRequestResponses().isEmpty()) {
            return mutableListOf();
        }
        val item = JMenuItem("Bulk Turbo")
        val resp = Resp(event.selectedRequestResponses()[0])
        item.addActionListener(TurboIntruderFrame(resp, IntArray(0), null, null, event.selectedRequestResponses()))
        return mutableListOf(item)
    }
}

class OfferTurboIntruderWithScript : ContextMenuItemsProvider {
    override fun provideMenuItems(event: ContextMenuEvent?): MutableList<Component> {
        if (event == null) return mutableListOf()
        val target = pickTarget(event) ?: return mutableListOf()
        val script = extractScriptFromNotes(target.annotations()?.notes()) ?: return mutableListOf()

        val item = JMenuItem("Send to turbo intruder (with script from notes)")
        val resp = Resp(target)
        item.addActionListener(TurboIntruderFrame(resp, IntArray(0), script, null, null))
        return mutableListOf(item)
    }

    private fun pickTarget(event: ContextMenuEvent): HttpRequestResponse? {
        val editor = event.messageEditorRequestResponse().getOrNull()
        if (editor != null) return editor.requestResponse()
        val selected = event.selectedRequestResponses()
        if (selected != null && selected.isNotEmpty()) return selected[0]
        return null
    }
}