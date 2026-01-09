package mcp

import burp.Utils
import burp.api.montoya.organizer.OrganizerItemFilter

data class OrganizerItemData(
    val id: Int,
    val request: String,
    val response: String,
    val notes: String
)

interface OrganizerProvider {
    fun getItems(): List<OrganizerItemData>
    fun getItemsByIds(ids: Set<Int>): List<OrganizerItemData>
    fun setNotes(id: Int, notes: String): Boolean
}

class BurpOrganizerProvider : OrganizerProvider {
    override fun getItems(): List<OrganizerItemData> {
        val organizer = Utils.montoyaApi?.organizer() ?: return emptyList()
        return organizer.items().map { item ->
            OrganizerItemData(
                id = item.id(),
                request = item.request()?.toString() ?: "",
                response = item.response()?.toString() ?: "",
                notes = item.annotations()?.notes() ?: ""
            )
        }
    }

    override fun getItemsByIds(ids: Set<Int>): List<OrganizerItemData> {
        val organizer = Utils.montoyaApi?.organizer() ?: return emptyList()
        val filter = OrganizerItemFilter { item -> item.id() in ids }
        return organizer.items(filter).map { item ->
            OrganizerItemData(
                id = item.id(),
                request = item.request()?.toString() ?: "",
                response = item.response()?.toString() ?: "",
                notes = item.annotations()?.notes() ?: ""
            )
        }
    }

    override fun setNotes(id: Int, notes: String): Boolean {
        val organizer = Utils.montoyaApi?.organizer() ?: return false
        val filter = OrganizerItemFilter { item -> item.id() == id }
        val item = organizer.items(filter).firstOrNull() ?: return false
        item.annotations()?.setNotes(notes)
        return true
    }
}
