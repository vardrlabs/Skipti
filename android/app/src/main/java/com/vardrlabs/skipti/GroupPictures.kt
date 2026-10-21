package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

/**
 * D-187: a group's picture (the founder, 2026-09-30: "an option to add logo
 * or photo to the group as a personal touch"; decided: a photo or an emoji).
 * The picture is a GROUP record (core's `GroupOp::GroupPicture`), sealed like
 * every group record, so every member's phone shows it and the relay sees
 * only ciphertext. iOS's twin is GroupPictures.swift.
 */
@Serializable
data class PictureDTO(val emoji: String? = null, val photoHex: String? = null)

object GroupPictures {
    /** The emoji offered, in this order on both phones. */
    val EMOJI = listOf(
        "✈️", "🏖️", "⛰️", "🏕️", "🚗", "🚆", "🏠", "🛒",
        "🍕", "🍜", "☕", "🍻", "🎉", "🎂", "🎁", "🎬",
        "⚽", "🏋️", "🎓", "💼", "💡", "🐶", "🎤", "❤️",
    )
    const val TITLE = "Group picture"
    const val CHOOSE_PHOTO = "Choose a photo"
    const val USE_LETTER = "Use the letter"
    const val CANCEL = "Cancel"
    const val TOO_BIG = "That photo could not be made small enough. Try another."
    fun spokenEmoji(emoji: String) = "Use $emoji"

    /** The largest photo core takes (`PICTURE_MAX_BYTES`). */
    const val MAX_BYTES = 64_000
    /** A photo is kept as a 3:2 landscape (the founder, 2026-09-30: the
     *  photo fills the group's card), sharp enough to cover a card. */
    const val WIDTH = 768
    const val HEIGHT = 512
    /** The widths tried in turn: a busy photo (a field of flowers) does not
     *  fit 64,000 bytes at 768 even at the lowest quality, so it steps down
     *  rather than being refused (measured, D-187). */
    val WIDTHS = listOf(768, 640, 512, 384)

    /** The largest 3:2 rectangle centred in a `w` × `h` image:
     *  [x, y, width, height]. iOS's twin is `GroupPictures.coverCrop`. */
    fun coverCrop(w: Int, h: Int): List<Int> =
        if (w * 2 >= h * 3) {
            val cw = h * 3 / 2
            listOf((w - cw) / 2, 0, cw, h)
        } else {
            val ch = w * 2 / 3
            listOf(0, (h - ch) / 2, w, ch)
        }

    /** Every group's picture, by group id; loaded from the vault on each
     *  sync (SyncStatus.revision) and after a change here. */
    var byId by mutableStateOf<Map<String, PictureDTO>>(emptyMap())
        private set

    private val bitmaps = HashMap<String, ImageBitmap>()

    /** D-191: creates this object's Compose state on the main thread, outside
     *  any composition; MainActivity.onCreate calls it before the first frame. */
    fun createState() = Unit

    fun load(store: LedgerStore) {
        byId = runCatching { store.groupPictures() }.getOrDefault(emptyMap())
    }

    /** A photo's pixels, decoded once per distinct photo. D-235 (H2): through [SafeImage] (a member wrote it): a
     *  picture past core's bounds, or one that will not decode, is null and the tile shows the group's letter. */
    fun bitmap(photoHex: String): ImageBitmap? = bitmaps.getOrPut(photoHex) {
        SafeImage.decodeHex(photoHex, SafeImage.PHOTO, SafeImage.TILE_TARGET)?.asImageBitmap() ?: return null
    }

    /**
     * A chosen photo made into what a group carries: its centre 3:2 crop,
     * 768 by 512 (smaller only when a busy photo will not fit), as a JPEG at the best quality that fits `MAX_BYTES`. The
     * system decoder applies the photo's own orientation, and the pixels are
     * encoded afresh, so nothing of the original file (its location, its
     * camera) comes along. Null when it will not decode or fit.
     */
    fun prepare(context: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
        val full = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val shortest = minOf(info.size.width, info.size.height)
                if (shortest > 1536) decoder.setTargetSampleSize(shortest / 768)
            }
        } else {
            // Android 8 and 8.1 have no ImageDecoder; a camera photo whose
            // orientation is only in its EXIF may show turned (§4).
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            } ?: return@runCatching null
        }
        val (x, y, w, h) = coverCrop(full.width, full.height)
        val crop = android.graphics.Bitmap.createBitmap(full, x, y, w, h)
        for (width in WIDTHS) {
            val small = android.graphics.Bitmap.createScaledBitmap(crop, width, width * 2 / 3, true)
            for (quality in listOf(80, 70, 60, 50, 40)) {
                val out = java.io.ByteArrayOutputStream()
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_BYTES) return@runCatching out.toByteArray()
            }
        }
        null
    }.getOrNull()
}

/** The chooser: a photo, one of the emoji, or the letter back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupPictureDialog(
    dark: Boolean,
    onPhoto: () -> Unit,
    onEmoji: (String) -> Unit,
    onLetter: () -> Unit,
    onDismiss: () -> Unit,
) {
    SkiptiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(GroupPictures.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryAction(GroupPictures.CHOOSE_PHOTO, dark, onClick = onPhoto)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (e in GroupPictures.EMOJI) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onEmoji(e) }
                                .semantics { contentDescription = GroupPictures.spokenEmoji(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 24.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLetter) { Text(GroupPictures.USE_LETTER) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(GroupPictures.CANCEL) } },
    )
}

/** D-187: opens the chooser; `onChosen(emoji, jpeg)` has both null for the
 *  letter. A photo that will not decode or fit calls `onError`. */
@Composable
fun rememberGroupPictureChooser(
    dark: Boolean,
    onChosen: (String?, ByteArray?) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { GroupPictures.prepare(context, uri) }
            if (jpeg != null) onChosen(null, jpeg) else onError(GroupPictures.TOO_BIG)
        }
    }
    if (open) {
        GroupPictureDialog(
            dark,
            onPhoto = {
                open = false
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onEmoji = { open = false; onChosen(it, null) },
            onLetter = { open = false; onChosen(null, null) },
            onDismiss = { open = false },
        )
    }
    return { open = true }
}

/**
 * D-187 (the founder, 2026-09-30: "the entire card looks like that photo with
 * intelligent texts over it so that it doesn't blend in"): a group with a
 * photo shows it edge to edge under a black gradient, and its words are
 * white. The words never depend on the photo: they sit only where the
 * gradient is at least `TEXT_ALPHA` black, and white there clears 4.5:1 even
 * over a pure white photo (`worstContrast`, measured in the tests). The top
 * `BAND` holds only the "Showing" chip, on its own capsule. iOS's twin is
 * `PhotoCard` in GroupPictures.swift.
 */
object PhotoCard {
    const val TOP_ALPHA = 0.25f
    const val TEXT_ALPHA = 0.55f
    const val BOTTOM_ALPHA = 0.72f
    const val CHIP_ALPHA = 0.55f
    /** dp from the top where the gradient reaches `TEXT_ALPHA`. */
    const val BAND = 56
    const val CARD_HEIGHT = 168
    const val HEADER_HEIGHT = 176

    /** WCAG contrast of white over `alpha` black laid on a white photo, the
     *  brightest a photo can be. */
    fun worstContrast(alpha: Double): Double {
        val c = 1 - alpha
        val linear = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        return 1.05 / (linear + 0.05)
    }
}

/** A group's photo edge to edge, under `PhotoCard`'s gradient. */
@Composable
fun PhotoBackdrop(photo: ImageBitmap, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics {}) {
        androidx.compose.foundation.Image(
            photo, contentDescription = null, modifier = Modifier.matchParentSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Column(Modifier.matchParentSize()) {
            Box(
                Modifier.fillMaxWidth().height(PhotoCard.BAND.dp).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = PhotoCard.TOP_ALPHA), Color.Black.copy(alpha = PhotoCard.TEXT_ALPHA))),
                ),
            )
            Box(
                Modifier.fillMaxWidth().weight(1f).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = PhotoCard.TEXT_ALPHA), Color.Black.copy(alpha = PhotoCard.BOTTOM_ALPHA))),
                ),
            )
        }
    }
}
