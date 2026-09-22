/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

fun openPersistedTreeDocument(
  context: Context,
  treeUriString: String,
  requireWrite: Boolean = false,
): DocumentFile? {
  if (treeUriString.isBlank()) return null

  val treeUri =
    runCatching { Uri.parse(treeUriString) }
      .getOrNull()
      ?: return null

  val hasPermission =
    context.contentResolver.persistedUriPermissions.any { permission ->
      permission.uri == treeUri &&
        permission.isReadPermission &&
        (!requireWrite || permission.isWritePermission)
    }

  if (!hasPermission) return null

  val root =
    runCatching { DocumentFile.fromTreeUri(context, treeUri) }
      .getOrNull()
      ?: return null

  val accessible =
    runCatching { root.exists() && root.isDirectory && root.canRead() }
      .getOrDefault(false)

  return root.takeIf { accessible }
}

/**
 * Best-effort conversion of an SAF tree URI (e.g.
 * content://com.android.externalstorage.documents/tree/primary:mpv) into a real
 * filesystem path (/storage/emulated/0/mpv).
 *
 * Returns null when the authority/volume cannot be resolved. This is used as a
 * fallback on Android TV, where the document-tree picker (and therefore any
 * persisted SAF permission) is unavailable, but the app holds broad storage
 * access via READ/WRITE_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE.
 */
@Suppress("DEPRECATION")
fun treeUriToFilePath(treeUriString: String?): String? {
  if (treeUriString.isNullOrBlank()) return null
  return runCatching {
    val uri = Uri.parse(treeUriString)
    val path = uri.path ?: return null
    val treePart = path.removePrefix("/tree/")
    val decoded = Uri.decode(treePart)
    val colonIdx = decoded.indexOf(':')
    if (colonIdx < 0) return null
    val root = decoded.substring(0, colonIdx)
    val rest = decoded.substring(colonIdx + 1).trimStart('/')
    val base =
      when (root) {
        "primary" -> Environment.getExternalStorageDirectory().absolutePath
        else -> "/storage/$root"
      }
    if (rest.isEmpty()) base else "$base/$rest"
  }.getOrNull()
}

fun listTreeFilesSafely(document: DocumentFile): Array<DocumentFile> =
  runCatching { document.listFiles() }
    .getOrDefault(emptyArray())
