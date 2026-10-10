package com.superboox.quickaccess

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast

/** Starting apps, files and intents from a Service context. */
object Launcher {

    fun launchApp(context: Context, value: String): Boolean {
        val intent = if (value.contains('/')) {
            val cn = ComponentName.unflattenFromString(value) ?: return fail(context, "Bad app: $value")
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
        } else {
            context.packageManager.getLaunchIntentForPackage(value)
        } ?: return fail(context, "App not installed: $value")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(context, intent)
    }

    fun openFile(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        return start(context, intent)
    }

    fun openIntentUri(context: Context, uri: String): Boolean {
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return fail(context, "Invalid intent: $uri")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        DebugLog.i("launch", intent.toUri(0))
        true
    } catch (e: ActivityNotFoundException) {
        DebugLog.w("launch", "no activity for ${intent.toUri(0)}", e)
        fail(context, "No app can open this")
    } catch (e: SecurityException) {
        DebugLog.w("launch", "not allowed: ${intent.toUri(0)}", e)
        fail(context, "Not allowed: ${e.message}")
    }

    fun appIcon(context: Context, value: String): Drawable? = runCatching {
        val pm = context.packageManager
        val cn = ComponentName.unflattenFromString(value)
        if (cn != null) pm.getActivityIcon(cn) else pm.getApplicationIcon(value)
    }.getOrNull()

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        if (DocumentsContract.isTreeUri(uri)) {
            // "primary:note/Exports" -> "Exports"
            return DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
                .ifEmpty { "Folder" }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun fail(context: Context, msg: String): Boolean {
        DebugLog.w("launch", msg)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        return false
    }
}
