package com.syni.sdk.keyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * ContentProvider for IPC between keyboard extension and host app.
 * Handles request/response communication for keyboard suggestions.
 */
class SyniContentProvider : ContentProvider() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val pendingResponses = ConcurrentHashMap<String, IPCResponse>()
    private var keyboardBridge: KeyboardBridge? = null

    private lateinit var uriMatcher: UriMatcher

    override fun onCreate(): Boolean {
        val authority = "${context?.packageName}.syni.provider"
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, KeyboardBridge.PATH_REQUESTS, CODE_REQUESTS)
            addURI(authority, "${KeyboardBridge.PATH_REQUESTS}/*", CODE_REQUEST_ID)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            CODE_REQUEST_ID -> {
                val requestId = uri.lastPathSegment ?: return null
                val response = pendingResponses.remove(requestId) ?: return null

                MatrixCursor(arrayOf(
                    KeyboardBridge.COLUMN_REQUEST_ID,
                    KeyboardBridge.COLUMN_DATA,
                    KeyboardBridge.COLUMN_TYPE
                )).apply {
                    addRow(arrayOf(
                        response.requestId,
                        json.encodeToString(response),
                        KeyboardBridge.TYPE_RESPONSE
                    ))
                }
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) return null

        return when (uriMatcher.match(uri)) {
            CODE_REQUESTS -> {
                val type = values.getAsString(KeyboardBridge.COLUMN_TYPE)
                if (type != KeyboardBridge.TYPE_REQUEST) return null

                val requestId = values.getAsString(KeyboardBridge.COLUMN_REQUEST_ID) ?: return null
                val data = values.getAsString(KeyboardBridge.COLUMN_DATA) ?: return null

                try {
                    val request = json.decodeFromString<IPCRequest>(data)

                    // Process the request and store the response
                    val bridge = getOrCreateBridge()
                    val response = runBlocking {
                        bridge.processIncomingRequest(request)
                    }

                    pendingResponses[requestId] = response

                    Uri.withAppendedPath(uri, requestId)
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return when (uriMatcher.match(uri)) {
            CODE_REQUEST_ID -> {
                val requestId = uri.lastPathSegment ?: return 0
                if (pendingResponses.remove(requestId) != null) 1 else 0
            }
            else -> 0
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_REQUESTS -> "vnd.android.cursor.dir/vnd.syni.request"
            CODE_REQUEST_ID -> "vnd.android.cursor.item/vnd.syni.request"
            else -> null
        }
    }

    private fun getOrCreateBridge(): KeyboardBridge {
        return keyboardBridge ?: synchronized(this) {
            keyboardBridge ?: KeyboardBridge(context!!).also {
                keyboardBridge = it
            }
        }
    }

    companion object {
        private const val CODE_REQUESTS = 1
        private const val CODE_REQUEST_ID = 2
    }
}
