package app.yolaq.mobile.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ShareOutput"

/** Where the app's own pictures land in the gallery. */
private const val GALLERY_FOLDER = "yolak"

/**
 * Getting the finished image off the phone.
 *
 * Two destinations, and they are genuinely different things: the share sheet
 * hands the image to Instagram or WhatsApp without it ever entering the
 * gallery, while saving puts it in the camera roll to be posted later or not
 * at all.
 */
object ShareOutput {

    /**
     * Writes the image where other apps can read it and opens the share sheet.
     *
     * The file goes in the cache: it exists to be handed to another app, and
     * keeping a copy the athlete never asked for is what makes a phone fill up
     * with images nobody remembers making. Saving to the gallery is the
     * separate, deliberate action.
     *
     * @param context Any context.
     * @param bitmap The finished image.
     * @param activityId Which outing, so repeated shares of the same one reuse
     *   the file rather than growing a pile.
     * @return True when the share sheet opened.
     */
    fun share(context: Context, bitmap: Bitmap, activityId: Long): Boolean {
        val directory = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(directory, "yolak-$activityId.png")
        val written = runCatching {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isFailure.not()
        if (!written) {
            Log.w(TAG, "Paylaşım görseli yazılamadı")
            return false
        }

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.shares", file)
        }.getOrElse {
            Log.w(TAG, "Paylaşım dosyası için URI alınamadı", it)
            return false
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Without this the receiving app gets a URI it is not allowed to
            // open, and the image arrives as a blank square.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /**
     * Whether this phone can be written to without asking for a storage
     * permission.
     *
     * Below Android 10 every gallery write needs `WRITE_EXTERNAL_STORAGE`,
     * which is a permission prompt for the whole of shared storage in exchange
     * for one picture. The share sheet already covers those phones — it hands
     * the image straight to the app it is going to — so the save button simply
     * does not appear rather than the app asking for the run of the device.
     */
    val canSaveToGallery: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Puts the image in the gallery.
     *
     * @param context Any context.
     * @param bitmap The finished image.
     * @param activityId Which outing, for the file name.
     * @return The saved image, or null when it could not be written.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, activityId: Long): Uri? {
        if (!canSaveToGallery) {
            return null
        }
        val name = "yolak-$activityId-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$GALLERY_FOLDER")
            // Hidden from the gallery until the bytes are actually there, so a
            // half-written picture is never shown.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return runCatching {
            resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: error("Çıkış akışı açılamadı")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        }.getOrElse {
            Log.w(TAG, "Görsel galeriye kaydedilemedi", it)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
