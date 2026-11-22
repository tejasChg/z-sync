package app.zsync.gallery

import app.zsync.log.QuickLog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.zsync.link.LOG_TAG
import app.zsync.gallery.MediaCollection
import app.zsync.gallery.ByteSource
import app.zsync.gallery.jpegSiblingName
import app.zsync.gallery.largestEmbeddedJpeg
import app.zsync.gallery.StoredMedia
import app.zsync.gallery.mediaCollection
import app.zsync.gallery.mediaFolder
import app.zsync.gallery.mediaMime
import app.zsync.gallery.sameSavedFile
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.io.OutputStream

/** What the album can offer for one saved row. */
sealed interface SavedOriginal {
    /** Readable and decodable here. */
    data class Jpeg(val bytes: ByteArray) : SavedOriginal

    /** In the album, but a RAW this app cannot draw. */
    data object Raw : SavedOriginal

    /** Not in the album at all. */
    data object Missing : SavedOriginal
}

class PhoneGallery(private val resolver: ContentResolver) {
    fun alreadyThere(file: CameraFile): Boolean {
        val collection = mediaCollection(file.format)
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val size = MediaStore.MediaColumns.SIZE
        val mime = MediaStore.MediaColumns.MIME_TYPE
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val folder = mediaFolder(file.format)
        resolver.query(
            collectionUri(collection),
            arrayOf(name, size, mime),
            "$size=? AND $mime=? AND ($path=? OR $path=?)",
            arrayOf(file.bytes.toString(), mediaMime(file.format), "$folder/", folder),
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(name)
            val sizeColumn = cursor.getColumnIndexOrThrow(size)
            val mimeColumn = cursor.getColumnIndexOrThrow(mime)
            while (cursor.moveToNext()) {
                val stored = StoredMedia(
                    name = cursor.getString(nameColumn).orEmpty(),
                    bytes = cursor.getLong(sizeColumn),
                    mime = cursor.getString(mimeColumn).orEmpty(),
                    collection = collection,
                )
                if (sameSavedFile(file, stored)) return true
            }
        }
        return false
    }

    fun save(file: CameraFile, bytes: ByteArray): String {
        if (mediaCollection(file.format) == MediaCollection.Video) error("video stays on disk")
        if (bytes.size.toLong() != file.bytes) error("refuse partial ${file.name}")
        return writePending(file) { stream -> stream.write(bytes) }
    }

    fun save(file: CameraFile, source: File): String {
        if (source.length() != file.bytes) error("refuse partial ${file.name}")
        return writePending(file) { stream -> source.inputStream().use { input -> input.copyTo(stream) } }
    }

    /**
     * Saves a processed JPEG as a new file in Pictures/Z-Sync.
     *
     * The original image is never overwritten: [displayName] is only a request, and MediaStore adds
     * a " (1)" suffix if that name already exists. [exifSource] is used solely as a metadata source;
     * EXIF propagation is best-effort and a failure there is logged without failing the save.
     */
    fun saveProcessed(displayName: String, processedBytes: ByteArray, exifSource: ByteArray?): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Z-Sync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("相册拒绝了 $displayName")
        try {
            resolver.openOutputStream(uri)?.use { stream -> stream.write(processedBytes) }
                ?: error("写不进 $displayName")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            if (exifSource != null) copyExif(uri, displayName, exifSource)
        } catch (failed: Exception) {
            resolver.delete(uri, null, null)
            throw failed
        }
        return displayName
    }

    private fun copyExif(uri: Uri, displayName: String, exifSource: ByteArray) {
        try {
            val source = exifSource.inputStream().use { ExifInterface(it) }
            val targetFile = resolver.openFileDescriptor(uri, "rw")
            if (targetFile == null) {
                Log.w(LOG_TAG, "exif copy skipped: no file descriptor for $displayName")
                return
            }
            targetFile.use { pfd ->
                val target = ExifInterface(pfd.fileDescriptor)
                var copied = 0
                for (tag in PROCESSED_EXIF_TAGS) {
                    source.getAttribute(tag)?.let { value ->
                        target.setAttribute(tag, value)
                        copied += 1
                    }
                }
                if (copied > 0) target.saveAttributes()
            }
        } catch (failed: Exception) {
            Log.w(LOG_TAG, "exif copy failed for $displayName: ${failed.javaClass.simpleName}: ${failed.message}")
        }
    }

    /** Returns the display name MediaStore actually stored, which differs when it renames a collision. */
    private fun writePending(file: CameraFile, write: (OutputStream) -> Unit): String {
        val collection = mediaCollection(file.format)
        // Only the name, type and folder: leave the camera's own dates and metadata untouched.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaMime(file.format))
            put(MediaStore.MediaColumns.RELATIVE_PATH, mediaFolder(file.format))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collectionUri(collection), values)
            ?: error("相册拒绝了 ${file.name}")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("写不进 ${file.name}")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (failed: Exception) {
            resolver.delete(uri, null, null)
            throw failed
        }
        return displayName(uri) ?: file.name
    }

    private fun displayName(uri: Uri): String? {
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        return resolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(name)) else null
        }
    }

    fun videoUri(name: String): Uri? {
        val display = MediaStore.MediaColumns.DISPLAY_NAME
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val idColumn = MediaStore.MediaColumns._ID
        val folder = "Movies/Z-Sync"
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(idColumn),
            "$display=? AND ($path=? OR $path=?)",
            arrayOf(name, "$folder/", folder),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(idColumn))
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        }
        return null
    }

    /**
     * Pulls the JPEG preview that sits inside a saved RAW.
     *
     * Reads only the TIFF headers and then the preview bytes, never the whole 20–30 MB file: the
     * preview is 1–3 MB somewhere past the sensor data.
     */
    fun readEmbeddedPreview(name: String): ByteArray? {
        val hit = hits(MediaCollection.Images, name).firstOrNull() ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.id)
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val size = channel.size()
                    val source = ByteSource { offset, length ->
                        if (offset < 0 || offset >= size) {
                            ByteArray(0)
                        } else {
                            val capped = minOf(length.toLong(), size - offset).toInt()
                            val buffer = ByteBuffer.allocate(capped)
                            var read = 0
                            while (read < capped) {
                                val n = channel.read(buffer, offset + read)
                                if (n <= 0) break
                                read += n
                            }
                            buffer.array().copyOf(read)
                        }
                    }
                    val found = largestEmbeddedJpeg(source) ?: return@use null
                    if (found.length <= 0 || found.offset + found.length > size) {
                        QuickLog.e(LOG_TAG, "raw preview out of range name=$name")
                        return@use null
                    }
                    source.read(found.offset, found.length).takeIf { it.isNotEmpty() }
                }
            }
        } catch (failed: Exception) {
            QuickLog.e(LOG_TAG, "raw preview failed name=$name ${failed.javaClass.simpleName}: ${failed.message}")
            null
        }
    }

    /**
     * Reads the copy that is in the album, as far as it can be drawn here.
     *
     * A NEF cannot be decoded, so a RAW row only shows when the matching JPEG is also in the album.
     * The three answers are kept apart on purpose: telling the person "相册里没有这张原图" when the
     * RAW *is* in the album was wrong, and it was what every RAW-only download used to get.
     */
    fun readSaved(name: String): SavedOriginal {
        jpegBytes(name)?.let { return SavedOriginal.Jpeg(it) }
        jpegSiblingName(name)?.let { sibling ->
            jpegBytes(sibling)?.let { return SavedOriginal.Jpeg(it) }
        }
        return if (hits(MediaCollection.Images, name).isNotEmpty()) SavedOriginal.Raw else SavedOriginal.Missing
    }

    fun remove(name: String): Int {
        var removed = 0
        for (collection in MediaCollection.entries) {
            for (row in hits(collection, name)) {
                val uri = ContentUris.withAppendedId(collectionUri(collection), row.id)
                removed += resolver.delete(uri, null, null)
            }
        }
        QuickLog.i(LOG_TAG, "gallery delete name=$name rows=$removed")
        return removed
    }

    private fun jpegBytes(name: String): ByteArray? {
        val hit = hits(MediaCollection.Images, name).firstOrNull { row ->
            row.mime == "image/jpeg" || row.mime == "image/jpg" ||
                row.name.endsWith(".jpg", ignoreCase = true) ||
                row.name.endsWith(".jpeg", ignoreCase = true)
        } ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hit.id)
        return resolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun hits(collection: MediaCollection, name: String): List<Hit> {
        if (name.isBlank()) return emptyList()
        val display = MediaStore.MediaColumns.DISPLAY_NAME
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val id = MediaStore.MediaColumns._ID
        val mime = MediaStore.MediaColumns.MIME_TYPE
        val folder = when (collection) {
            MediaCollection.Images -> "Pictures/Z-Sync"
            MediaCollection.Video -> "Movies/Z-Sync"
        }
        val found = mutableListOf<Hit>()
        resolver.query(
            collectionUri(collection),
            arrayOf(id, display, mime),
            "$display=? AND ($path=? OR $path=?)",
            arrayOf(name, "$folder/", folder),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(id)
            val nameColumn = cursor.getColumnIndexOrThrow(display)
            val mimeColumn = cursor.getColumnIndexOrThrow(mime)
            while (cursor.moveToNext()) {
                found += Hit(
                    id = cursor.getLong(idColumn),
                    name = cursor.getString(nameColumn).orEmpty(),
                    mime = cursor.getString(mimeColumn),
                )
            }
        }
        return found
    }

    private fun collectionUri(collection: MediaCollection) = when (collection) {
        MediaCollection.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaCollection.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private data class Hit(val id: Long, val name: String, val mime: String?)
}

/** Metadata worth carrying from the original JPEG onto a processed copy. */
private val PROCESSED_EXIF_TAGS = arrayOf(
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
)
