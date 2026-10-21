package com.vardrlabs.skipti

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Google's own button, PLAY build (D-138): the "Sign in with Google" artwork
 * from Google's asset bundle, light and dark (`drawable-night`), used as
 * supplied. Google's branding rules forbid redrawing it, and the words are
 * part of the image, so it needs no font. D-188 (the founder: "bigger and
 * rounder around the corners"): the bundle's PILL art, scaled with its aspect
 * kept (Google allows scaling; a redrawn button would need Google Sans), 234 x
 * 52 dp from 180 x 40, each density filled from the next-larger file.
 * The direct flavour's file of the same name draws nothing.
 */
@Composable
fun GoogleSignInButton(enabled: Boolean, onClick: () -> Unit) {
    Image(
        painter = painterResource(R.drawable.google_sign_in),
        contentDescription = "Sign in with Google",
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .size(width = 234.dp, height = 52.dp)
            .alpha(if (enabled) 1f else 0.45f),
    )
}

/**
 * D-172: Google's round "G" (the bundle's icon-only art, light and dark, as
 * supplied) beside "Signed in with Google" in Settings and on the sign-in
 * confirmation. Decorative: the words beside it say the same.
 */
@Composable
fun GoogleMark(sizeDp: Int) {
    Image(
        painter = painterResource(R.drawable.google_mark),
        contentDescription = null,
        modifier = Modifier.size(sizeDp.dp),
    )
}
