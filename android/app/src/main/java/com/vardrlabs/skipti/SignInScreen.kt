package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D-188 (the founder, 2026-09-30: "the entire page is taken and only the top
 * part is filled"): what the sign-in page says around the button. It informs,
 * never nudges (GUARANTEES #5: the person already chose this screen). D-191:
 * the account is a Firebase account, and the words say where the data goes.
 * iOS's twin is `SignInCopy` in SignInScreen.swift.
 */
object SignInCopy {
    const val HERO_TITLE = "Your ledger, on every phone"
    const val HERO_BODY = "Signing in adds sync and groups. SKIPTI keeps working on this phone without it."
    const val STEPS_TITLE = "What happens when you sign in"

    /** The three steps, the first naming only the providers this build offers. */
    fun steps(apple: Boolean, google: Boolean): List<String> = listOf(
        when {
            apple && google -> "Apple or Google confirms it is you."
            apple -> "Apple confirms it is you."
            else -> "Google confirms it is you."
        },
        "Your ledger syncs to your other phones through SKIPTI's cloud, which runs on Google Firebase.",
        "You can make and join groups. Only the people in a group can see it.",
    )
}

/**
 * D-191: Sign in with Google through Firebase Auth (Credential Manager's
 * sheet, then Firebase). [onSignedIn] receives what happened: the caller shows
 * the confirmation (D-172), the other-account notice (D-168) or the failure.
 * A dismissed sheet is not an error; a device that cannot show Google's sheet
 * gets a device-scoped note.
 */
@Composable
fun SignInScreen(vaultPath: String, onBack: () -> Unit, onSignedIn: (Account.Outcome, SignInMethod) -> Unit) {
    val dark = isSystemInDarkTheme()
    var inFlight by rememberSaveable { mutableStateOf(false) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var localNote by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val platformContext = LocalContext.current

    fun useGoogle() {
        val webClientId = GoogleSignInConfig.WEB_CLIENT_ID ?: return
        if (inFlight) return
        inFlight = true
        errorText = null
        localNote = null
        scope.launch {
            try {
                val credential = googleCredential(platformContext, webClientId)
                val outcome = withContext(Dispatchers.IO) {
                    Account.signIn(credential, SignInMethod.Google, LedgerStore(vaultPath))
                }
                inFlight = false
                if (outcome is Account.Outcome.Failed) errorText = outcome.reason
                onSignedIn(outcome, SignInMethod.Google)
            } catch (_: CeremonyCancelled) {
                inFlight = false
            } catch (e: CeremonyUnavailable) {
                inFlight = false
                localNote = listOfNotNull(e.message, e.detail).joinToString("\n")
            } catch (e: Exception) {
                inFlight = false
                errorText = "Sign in with Google did not finish."
            }
        }
    }

    // D-217: the title and a back button are the bar's (this form had a 40 sp title and no on-screen back).
    val scroll = rememberScrollState()
    // D-220 (L-D217-3): the page is its bar's backdrop (Aurora blurs it under the bar).
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = BarDecisions.CONTENT_TOP_PAD.dp, bottom = 16.dp)
        ) {
            // D-188: what signing in is for, above the button.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heroArt(dark, ThemeArt.cardShape(Appearance.theme))
                    .padding(20.dp)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    SignInCopy.HERO_TITLE,
                    style = Type.display(dark).copy(
                        fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Tok.onHero(dark),
                    ),
                )
                Text(SignInCopy.HERO_BODY, style = Type.body(false).copy(color = Tok.onHero(dark)))
            }
            Spacer(Modifier.height(20.dp))
            if (GoogleSignInConfig.isAvailable) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GoogleSignInButton(enabled = !inFlight) { useGoogle() }
                }
                Spacer(Modifier.height(20.dp))
                // D-188, D-191: who confirms it is you, and what an account adds.
                Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(SignInCopy.STEPS_TITLE, style = Type.label(dark, 17))
                    SignInCopy.steps(apple = false, google = true).forEachIndexed { i, step ->
                        Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier.size(28.dp).background(Tok.accentSoft(dark), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("${i + 1}", style = Type.label(dark, 14).copy(color = Tok.accent(dark))) }
                            Text(step, style = Type.bodyDim(dark), modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            errorText?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(
                    msg,
                    style = Type.body(dark).copy(color = Tok.owed(dark)),
                    modifier = Modifier.semantics { contentDescription = "Sign-in failed. $msg" },
                )
            }
            localNote?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(msg, style = Type.bodyDim(dark))
            }
        }
        PushedBar("Sign in", onBack, scroll = scroll, backdrop = backdrop)
    }
}
