package com.vardrlabs.skipti

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.skipti_core.GroupOpIn

/**
 * D-145: the on-device profile, the twin of iOS `Profile.swift` (the
 * founder, 2026-09-21: a local profile with a name and "an option to add
 * photo like an emoji"; he chose emoji OR photo). It is NOT an account
 * (GUARANTEES #5) and never asks for one: without an account nothing here
 * leaves the phone; with one, the name and picture sync as below (D-211).
 *
 * The name is D-136's `SelfDTO.name` (`setYourName` renames you in every
 * group). The picture is new: an emoji from the fixed set iOS offers, or a
 * photo from the system photo picker (no permission: the picker hands over
 * only the one photo chosen), cropped square and shrunk to 256 px before it
 * is stored in the vault.
 */
sealed class Avatar {
    object None : Avatar()
    data class Emoji(val emoji: String) : Avatar()
    class Photo(val jpeg: ByteArray) : Avatar() {
        override fun equals(other: Any?) = other is Photo && other.jpeg.contentEquals(jpeg)
        override fun hashCode() = jpeg.contentHashCode()
    }

    @Serializable
    private data class Stored(val kind: String, val emoji: String? = null, val jpeg: String? = null)

    /** The record's two fields: an emoji, or a photo as lowercase hex, or
     *  neither (`None` clears the picture). */
    val recordParts: Pair<String?, String?>
        get() = when (this) {
            None -> null to null
            is Emoji -> emoji to null
            is Photo -> null to HexCodec.encode(jpeg)
        }

    fun encoded(): ByteArray = JSON.encodeToString(
        Stored.serializer(),
        when (this) {
            None -> Stored("none")
            is Emoji -> Stored("emoji", emoji = emoji)
            is Photo -> Stored("photo", jpeg = Base64.getEncoder().encodeToString(jpeg))
        },
    ).toByteArray()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** A picture as the projection reports it; unreadable hex is no picture. */
        fun from(emoji: String?, photoHex: String?): Avatar = when {
            emoji != null -> Emoji(emoji)
            photoHex != null -> runCatching { Photo(HexCodec.decode(photoHex)) }.getOrNull() ?: None
            else -> None
        }

        /** Anything unreadable is no picture, never a crash: a picture is
         *  decoration, and the ledger must not fail to open over one. */
        fun decode(bytes: ByteArray?): Avatar {
            val s = runCatching { JSON.decodeFromString(Stored.serializer(), (bytes ?: return None).decodeToString()) }
                .getOrNull() ?: return None
            return when (s.kind) {
                "emoji" -> s.emoji?.takeIf { it in ProfileRules.EMOJI_CHOICES }?.let { Emoji(it) } ?: None
                "photo" -> s.jpeg?.let { runCatching { Photo(Base64.getDecoder().decode(it)) }.getOrNull() } ?: None
                else -> None
            }
        }
    }
}

/** D-225 (L76, .drafts/d225/art.py section 43): the emoji picker's grid, `emojiColumns` (6) equal columns on both phones
 *  (eight gave cells under both platforms' touch minimums). The twin of iOS's `EmojiGrid` (Profile.swift). */
/** D-225 fix wave (F5; the paired look: the name card 194.3 pt on iOS, 183.6 dp here): Profile's cards space their
 *  lines as iOS's `card` VStack (12, it was 10 here) and the name field is iOS's body at 17 (16 here); ProfileTest reads
 *  both out of Profile.swift. */
object ProfileLayout {
    const val CARD_SPACING = 12
    const val NAME_FIELD_SIZE = 17f
}

object EmojiGrid {
    /** The choices in rows of [columns], in order; the last row holds what is left. */
    fun rows(choices: List<String>, columns: Int): List<List<String>> = choices.chunked(columns.coerceAtLeast(1))
}

/** D-225 (L76): how an emoji cell is painted: the chosen cell `accentSoft` inside an `emojiBorder` border in `accent`;
 *  every other cell clear (no fill, no border). The twin of iOS's `EmojiCell` (its token names are these tokens' names). */
object EmojiCell {
    data class Paint(val fill: Tok?, val border: Tok?)

    fun paint(chosen: Boolean): Paint = if (chosen) Paint(Tok.accentSoft, Tok.accent) else Paint(null, null)
}

object ProfileRules {
    /** The emoji offered: iOS's fixed set, in iOS's order. */
    val EMOJI_CHOICES: List<String> = listOf(
        "😀", "😎", "🤓", "🥳", "😇", "🤠", "🧐", "😺",
        "🦊", "🐼", "🐯", "🦁", "🐸", "🐙", "🦉", "🐝",
        "🌻", "🌵", "🍀", "🌊", "🔥", "⭐", "🌙", "☀️",
        "🍕", "🍜", "🍩", "☕", "🍉", "🥑", "🌮", "🍣",
        "⚽", "🏏", "🎸", "🎧", "📚", "✈️", "🚲", "🏔️",
        "💼", "🎨", "🧭", "🪴", "🐾", "💡", "🎯", "🧩",
    )

    const val PHOTO_SIDE = 256

    /** Initials for a picture-less profile: the first letter of up to two
     *  words, or null when there is no name to take them from. */
    fun initials(name: String?): String? {
        val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (words.isEmpty()) return null
        return words.take(2).joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)) }.uppercase()
    }

    /** The centred square inside a `width` × `height` image: x, y, side. */
    fun squareCrop(width: Int, height: Int): Triple<Int, Int, Int> {
        val side = minOf(width, height)
        return Triple((width - side) / 2, (height - side) / 2, side)
    }

    /** The largest photo core takes (`PICTURE_MAX_BYTES`). */
    const val PHOTO_MAX_BYTES = 64_000

    /** A chosen photo as the stored picture: the right way up, centre-cropped
     *  square, at most [PHOTO_SIDE] pixels, JPEG at the best quality (80,
     *  stepping down by 10 to 40) that fits [PHOTO_MAX_BYTES]. Null when not
     *  an image or it never fits. */
    fun storedPhoto(context: Context, uri: Uri): ByteArray? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        storedPhoto(bytes)
    }.getOrNull()

    /** The same for bytes already in hand (an old kept photo with no size cap
     *  is stepped down to what core takes). */
    fun storedPhoto(bytes: ByteArray): ByteArray? =
        squareJPEG(bytes, PHOTO_SIDE, PHOTO_MAX_BYTES, startQuality = 80, minQuality = 40)

    /**
     * The one square-JPEG helper (D-211): your stored photo and a group's
     * preview of it ([ProfileThumb]) both come through here. The picture is
     * decoded the right way up (its EXIF orientation applied), centre-cropped
     * square, drawn at [side] pixels (never more than the square's own side:
     * a small picture is never enlarged) and encoded as a JPEG at the best
     * quality from [startQuality] stepping down by 10 to [minQuality] that
     * fits [maxBytes]. Null when the bytes are not an image or never fit.
     * Android's Bitmap is needed, so the pixel work is seen on the emulator;
     * the rule it follows is [squareJpegSteps], tested.
     */
    fun squareJPEG(bytes: ByteArray, side: Int, maxBytes: Int, startQuality: Int, minQuality: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val degrees = java.io.ByteArrayInputStream(bytes).use {
            when (android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        val upright = if (degrees == 0f) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        val (x, y, squareSide) = squareCrop(upright.width, upright.height)
        val square = Bitmap.createBitmap(upright, x, y, squareSide, squareSide)
        var drawnAt = -1
        var scaled: Bitmap? = null
        squareJpegSteps(squareSide, side, maxBytes, startQuality, minQuality) { drawn, quality ->
            if (drawn != drawnAt) {
                scaled = Bitmap.createScaledBitmap(square, drawn, drawn, true)
                drawnAt = drawn
            }
            ByteArrayOutputStream().use { stream ->
                scaled?.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
        }
    }.getOrNull()

    /**
     * The rule [squareJPEG] follows, apart from the pixels: the square is
     * drawn at `min(side, squareSide)` (never enlarged), and [encode] is asked
     * for that drawing at quality [startQuality], then 10 lower each time,
     * down to [minQuality], until a result has at most [maxBytes]. Null when
     * none does (or [squareSide] is not positive).
     */
    fun squareJpegSteps(
        squareSide: Int, side: Int, maxBytes: Int, startQuality: Int, minQuality: Int,
        encode: (drawn: Int, quality: Int) -> ByteArray?,
    ): ByteArray? {
        if (squareSide <= 0) return null
        val drawn = minOf(side, squareSide)
        var quality = startQuality
        while (quality >= minQuality) {
            encode(drawn, quality)?.let { if (it.size <= maxBytes) return it }
            quality -= 10
        }
        return null
    }
}

/** Bumped on every profile change, so the top-right picture reloads. */
object ProfileState {
    var revision by mutableStateOf(0)
}

const val AVATAR_KEY = "ledger/self-avatar"

fun LedgerStore.loadAvatar(): Avatar = Avatar.decode(rawKvGet(AVATAR_KEY))

/** D-211: each group's joined people with a profile, by group id then by the
 *  id the person is SHOWN by: their preview (`thumbHex` in the photo field).
 *  Rebuilt from the records with the groups. */
const val MEMBER_PREVIEWS_KEY = "ledger/member-previews"

/**
 * D-211: your picture is a personal RECORD (core's `GroupOp::Avatar`), so it
 * syncs to your own phones and travels in the export; the kv value is a cache
 * the ledger rebuild writes from the records, as `ledger/self` is for the
 * name. Writes the record (clearing writes an empty one), then the kv cache the
 * screens read. Core refuses a photo over 64,000 bytes or that is not a JPEG:
 * nothing is then written. Your groups see a small preview of it, sent through
 * [ProfileShare].
 */
fun LedgerStore.saveAvatar(avatar: Avatar) {
    val (emoji, photoHex) = avatar.recordParts
    appendGroupOps(listOf(GroupOpIn.Avatar(emoji, photoHex)), Placing.NONE)
    rawKvSet(AVATAR_KEY, avatar.encoded())
    // A record now exists: the one-time migration has nothing left to do.
    rawKvSet(LedgerStore.AVATAR_RECORD_KEY, byteArrayOf(1))
}

/**
 * D-211: the previews of the joined people in each group, by group id, then by
 * the id the person is shown by. Present means that person has a profile of
 * their own (their name is theirs: Rename is absent); `Avatar.None` is a name
 * with no picture.
 */
fun LedgerStore.memberPreviews(): Map<String, Map<String, Avatar>> {
    val data = rawKvGet(MEMBER_PREVIEWS_KEY)?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    val stored = runCatching { LedgerStore.decodePreviews(data) }.getOrNull() ?: return emptyMap()
    return stored.mapValues { (_, people) -> people.mapValues { (_, p) -> Avatar.from(p.emoji, p.photoHex) } }
}

/** The picture: a photo, an emoji, your initials, or the person glyph. */
@Composable
fun AvatarView(avatar: Avatar, name: String?, size: Dp, dark: Boolean, fill: AvatarFill = AvatarStyle.fill(badge = false)) {
    // D-221 (Q2 a, K06): the fill's ground (the accent's soft fill, or the accent for the bar's badge); Norse alone
    // edges it in its gold-brown, the others draw no edge.
    val edge = AvatarStyle.edgeHex(Appearance.theme)?.let { Color(0xFF000000 or it.removePrefix("#").toLong(16)) }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill.ground(dark))
            .then(if (edge != null) Modifier.border(1.dp, edge, CircleShape) else Modifier)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when (avatar) {
            is Avatar.Photo -> {
                // D-235 (H2): a picture from the cloud (your other phone's, a member's preview) through SafeImage: past
                // core's bounds or undecodable, it is null and the circle stays its fill.
                val bitmap = remember(avatar) { SafeImage.decode(avatar.jpeg, SafeImage.PHOTO, SafeImage.AVATAR_TARGET) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // D-225 (Design 9, L75): iOS's ratio, the emoji at `avatarEmoji` of the size.
            is Avatar.Emoji -> Text(avatar.emoji, fontSize = (size.value * ThemeArt.m("avatarEmoji")).sp)
            Avatar.None -> {
                val initials = ProfileRules.initials(name)
                if (initials != null) {
                    // D-221 (K05): the display face at 700 in every theme, in the fill's ink.
                    Text(
                        initials,
                        style = AvatarStyle.initialStyle(dark, size.value).copy(color = fill.ink(dark)),
                    )
                } else {
                    // D-221 fix wave (S11), D-225 (L75): `avatarGlyph` (half) of the avatar, as iOS's Lucide user-round.
                    androidx.compose.material3.Icon(
                        imageVector = TabIcons.UserRound,
                        contentDescription = null,
                        tint = fill.ink(dark),
                        modifier = Modifier.size((size.value * ThemeArt.m("avatarGlyph")).dp),
                    )
                }
            }
        }
    }
}

/** D-211: Profile's footnotes. iOS's twin is ProfileCopy in Profile.swift. */
object ProfileCopy {
    const val SECTION_TITLE = "Name and picture"
    const val ACCOUNT_PICTURE = "Your picture syncs to your phones. Your groups see a small preview."
    const val ACCOUNT_HISTORY = "Earlier pictures stay in your private history until you delete your account."
    const val PHONE_ONLY = "Your name and picture stay on this phone."
    const val DONE = "Done"
    /** Not in the founder's copy table; the same on iOS (the picture button and the viewer's image). */
    const val YOUR_PICTURE = "Your picture"

    /** What the picture does depends on whether an account exists; without
     *  one no cloud word appears (GUARANTEES #5). */
    fun footnotes(signedIn: Boolean): List<String> =
        if (signedIn) listOf(ACCOUNT_PICTURE, ACCOUNT_HISTORY) else listOf(PHONE_ONLY)
}

/** D-211: the viewer is offered only for a photo whose bytes decode to an
 *  image; any other `Photo` behaves like no picture. [decodes] is the seam
 *  the JVM tests replace (Bitmap needs a device). */
object PictureViewing {
    fun decodes(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && SafeImage.decode(bytes, SafeImage.PHOTO, SafeImage.VIEWER_TARGET) != null

    fun canView(avatar: Avatar, decodes: (ByteArray) -> Boolean = ::decodes): Boolean =
        avatar is Avatar.Photo && decodes(avatar.jpeg)

    /** The open viewer closes when the picture stops being a photo, but never
     *  before the picture was first read: until then [viewable] is false only
     *  because the placeholder is showing (a restored viewer must survive). */
    fun shouldClose(viewable: Boolean, pictureRead: Boolean): Boolean = pictureRead && !viewable
}

/** D-211: your own picture, big, on black. Only a photo opens it (an emoji or
 *  no picture has nothing to enlarge). Nothing here changes the picture. A
 *  full-screen dialog, not the platform's narrow one. */
@Composable
fun PictureViewer(avatar: Avatar, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        SkiptiWindowLocals {
            // D-211 fix 5: the dialog's own window draws behind both system bars
            // (no grey scrim bands), with light bar icons on the black.
            val view = androidx.compose.ui.platform.LocalView.current
            SideEffect {
                (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        window.isNavigationBarContrastEnforced = false
                        window.isStatusBarContrastEnforced = false
                    }
                    androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                val bitmap = remember(avatar) {
                    (avatar as? Avatar.Photo)?.let { SafeImage.decode(it.jpeg, SafeImage.PHOTO, SafeImage.VIEWER_TARGET) }
                }
                if (bitmap != null) {
                    // The picture stays inside the safe area; the black goes to the edges.
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = ProfileCopy.YOUR_PICTURE,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp, end = 8.dp),
                ) {
                    Text(ProfileCopy.DONE, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** D-217 (the Look Book's `.hello b`): the greeting is 22 at 700; Norse's Cormorant SC is tracked +.02 em. */
object GreetingStyle {
    const val SIZE = 22f
    fun tracking(theme: AppTheme): androidx.compose.ui.unit.TextUnit =
        if (theme == AppTheme.NORSE) 0.02.em else androidx.compose.ui.unit.TextUnit.Unspecified

    fun style(dark: Boolean) = Type.display(dark, 700).copy(fontSize = SIZE.sp, letterSpacing = tracking(Appearance.theme))
}

/** D-206: the top of Balance: today's date and a greeting by the phone's
 *  clock and the name on this phone (Greeting). Nothing leaves the phone.
 *  The twin of iOS's HomeGreeting. */
@Composable
fun HomeGreeting(vaultPath: String, dark: Boolean) {
    var name by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(vaultPath, ProfileState.revision) {
        now = java.time.LocalDateTime.now()
        name = withContext(Dispatchers.IO) { runCatching { LedgerStore(vaultPath).loadSelf()?.name }.getOrNull() }
    }
    // The locale's own order (iOS's .dateTime.weekday(.wide).day().month(.wide)).
    val date = remember(now) {
        val locale = java.util.Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        now.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        Text(Type.kicker(date), style = Type.microLabel(dark).copy(fontSize = TypeScale.dateLine.sp))
        Text(Greeting.line(now.hour, name), style = GreetingStyle.style(dark))
    }
}

/** Settings → Profile (route "profile"). D-245 (U17, decisions 161, 179): a tapped emoji or a picked photo is staged and
 *  confirmed in a sheet ([PictureConfirmSheet]); "Use this picture" writes it once, "Not now" writes nothing; the name
 *  is checked in words, saved by the primary "Save name" or the keyboard's Done, and confirmed by a done row that stays
 *  until the field changes. The twin of iOS's `ProfileScreen`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(vaultPath: String, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var savedName by remember { mutableStateOf<String?>(null) }
    var avatar by remember { mutableStateOf<Avatar>(Avatar.None) }
    var error by remember { mutableStateOf<String?>(null) }
    // D-245 (U17): the name the done row confirms; the row shows while the field still holds it.
    var nameDoneFor by remember { mutableStateOf<String?>(null) }
    // D-245 (decision 161): a tapped emoji or picked photo waits in the sheet; nothing is written before "Use this
    // picture". The done row under the picture says what the last confirm wrote.
    var stage by remember { mutableStateOf(PictureStage()) }
    var confirming by remember { mutableStateOf<Avatar?>(null) }
    // D-211: the footnotes follow the account live, as the hub does: read again
    // when a sign-out happens while this screen is open.
    var signedIn by remember { mutableStateOf(runCatching { Account.isSignedIn() }.getOrDefault(false)) }
    LaunchedEffect(RemoteSignOut.signOuts) { signedIn = runCatching { Account.isSignedIn() }.getOrDefault(false) }

    LaunchedEffect(vaultPath) {
        withContext(Dispatchers.IO) {
            val store = LedgerStore(vaultPath)
            runCatching {
                val me = store.ensureSelf()
                savedName = me.name
                name = me.name ?: ""
                avatar = store.loadAvatar()
            }.onFailure { error = it.message ?: it.toString() }
        }
    }

    /** D-245 (decision 161): a tap or a pick only stages the picture and opens the confirm. */
    fun stagePicture(new: Avatar) {
        error = null
        stage = stage.choose(new)
        confirming = new
    }

    /** The confirmed picture (or the removal): written once (D-211: one record, one share request; D-224's toast). */
    fun usePicture(new: Avatar) {
        error = null
        confirming = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { ProfileSave.picture(new, LedgerStore(vaultPath), ProfileSave.Effects.live(vaultPath)) }.exceptionOrNull()
            }
            if (failure == null) {
                avatar = new
                stage = stage.used(new)
                ProfileSave.done(ProfileSave.pictureMoment(new), ProfileSave.Feedback.TOAST)
            } else {
                stage = stage.notNow()
                error = failure.message ?: failure.toString()
            }
        }
    }

    /** D-245 (decision 179): the rules in words, then the one save (D-211: your other phones and your groups; D-224's
     *  toast). */
    fun saveName() {
        error = null
        val typed = name
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                ProfileSave.name(typed, LedgerStore(vaultPath), ProfileSave.Effects.live(vaultPath))
            }
            when (outcome) {
                is ProfileSave.NameOutcome.Saved -> {
                    savedName = outcome.name
                    name = outcome.name
                    nameDoneFor = outcome.name
                    ProfileSave.done(ProfileSave.nameMoment, ProfileSave.Feedback.TOAST)
                }
                is ProfileSave.NameOutcome.Refused -> error = outcome.words
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stored = withContext(Dispatchers.IO) { ProfileRules.storedPhoto(context, uri) }
            if (stored != null) stagePicture(Avatar.Photo(stored)) else error = WelcomeCopy.PHOTO_UNREADABLE
        }
    }

    PushedPage("Profile", dark, onBack, spacing = 16.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AvatarView(avatar, savedName, 96.dp, dark)
            stage.done?.let { DoneRow(it, dark) }
        }

        Column(
            Modifier.glassCard(dark), // D-176
            verticalArrangement = Arrangement.spacedBy(ProfileLayout.CARD_SPACING.dp),
        ) {
            SectionLabel("Your name", dark)
            // D-225 (Design 9): iOS's hairline field, no outline. D-245: the keyboard's Done saves (iOS's Return did; the
            // brief's twin defect).
            HairlineField(
                name, { name = it; error = null }, "Your name", dark,
                style = Type.body(dark).copy(fontSize = ProfileLayout.NAME_FIELD_SIZE.sp),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                actions = KeyboardActions(onDone = { saveName() }),
            )
            // D-245 (U17): signed in, who sees the name.
            if (signedIn) Text(WelcomeCopy.GROUPS_NAME, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            savedName?.let { Text("Shown as “$it (You)” in your groups.", style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)) }
            // D-245 (U17, decision 179): the rules in words, as the field is typed.
            NameRule.liveProblem(name, savedName)?.let { Text(it.message, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp, color = Tok.owed(dark))) }
            // D-224 (Design 8): the toast says it; D-245 (U17): and the done row, until the field changes.
            if (nameDoneFor != null && nameDoneFor == NameRule.trimmed(name)) DoneRow(DoneCopy.NAME_SAVED, dark)
            // D-245 (U17): the primary style when there is a name to save; the secondary, dimmed, when not (D-220: full
            // width, as iOS's styles are).
            val savable = NameRule.dirty(name, savedName) && NameRule.check(name) == null
            if (savable) {
                PrimaryAction(WelcomeCopy.SAVE_NAME, dark) { saveName() }
            } else {
                SecondaryAction(WelcomeCopy.SAVE_NAME, dark, enabled = false) { saveName() }
            }
        }

        Column(
            Modifier.glassCard(dark), // D-176
            verticalArrangement = Arrangement.spacedBy(ProfileLayout.CARD_SPACING.dp),
        ) {
            SectionLabel("Picture", dark)
            // D-225 (Design 9, L76; art.py 43): `emojiColumns` equal square columns `emojiGap` apart; the chosen cell
            // `accentSoft` inside an `emojiBorder` border in `accent` ([EmojiCell]), in the theme's panel shape; the emoji at
            // a fixed `emojiGlyph` (it does not grow with the text size, as iOS's fixed 24).
            val gap = ThemeArt.m("emojiGap").dp
            val cell = ThemeArt.panelShape(Appearance.theme, ThemeArt.m("emojiRadius").toInt())
            val glyph = with(LocalDensity.current) { ThemeArt.m("emojiGlyph").dp.toSp() }
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                EmojiGrid.rows(ProfileRules.EMOJI_CHOICES, ThemeArt.m("emojiColumns").toInt()).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { e ->
                            val chosen = avatar == Avatar.Emoji(e)
                            val paint = EmojiCell.paint(chosen)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .then(paint.fill?.let { Modifier.background(it(dark), cell) } ?: Modifier)
                                    .then(paint.border?.let { Modifier.border(ThemeArt.m("emojiBorder").dp, it(dark), cell) } ?: Modifier)
                                    .clip(cell)
                                    // D-225 follow-up (R6): ONE node, the emoji with its chosen state and its tap (the
                                    // emoji's own Text made a second node with the same bounds), as the list rows are.
                                    // D-245 (decision 161): a tap stages the picture and opens the confirm.
                                    .clickable { stagePicture(Avatar.Emoji(e)) }
                                    .clearAndSetSemantics { contentDescription = e; selected = chosen },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = glyph) }
                        }
                        // A short last row keeps the columns' width.
                        repeat(ThemeArt.m("emojiColumns").toInt() - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            SecondaryAction("Choose a photo", dark) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            if (avatar != Avatar.None) {
                // D-245: removing keeps today's flow (no confirm), through the one save.
                SecondaryAction("Remove picture", dark) { usePicture(Avatar.None) }
            }
        }

        error?.let { Text(it, style = Type.bodyDim(dark).copy(color = Tok.owed(dark))) }
        // D-211: what the picture does depends on whether an account exists;
        // without one no cloud word appears (GUARANTEES #5).
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileCopy.footnotes(signedIn).forEach { line -> Text(line, style = Type.bodyDim(dark)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    // D-245 (decision 161): the confirm, shown while a picture is staged; dismissing it is "Not now".
    confirming?.let { staged ->
        PictureConfirmSheet(
            staged, savedName, signedIn,
            onUse = { usePicture(staged) },
            onNotNow = {
                stage = stage.notNow()
                confirming = null
            },
        )
    }
}
