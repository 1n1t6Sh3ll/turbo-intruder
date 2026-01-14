package mcp

import burp.ResultStore
import burp.RunHandler
import java.util.UUID

class ActiveRun(
    val ownerSessionId: String
) {
    val id: String = UUID.randomUUID().toString()
    val handler: RunHandler = RunHandler()
    val store: ResultStore = ResultStore()
    val createdAt: Long = System.currentTimeMillis()
}
