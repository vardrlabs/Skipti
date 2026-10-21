package com.vardrlabs.skipti

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Firebase, configured in code (D-191); iOS's twin is FirebaseConfig.swift.
 * The values are public identifiers from `firebase apps:sdkconfig ANDROID`
 * for project skipti-26; google-services.json (it carries the SHA-1s) stays
 * out of git, and no google-services Gradle plugin is used.
 */
object FirebaseConfig {
    const val PROJECT_ID = "skipti-26"
    const val APP_ID = "1:403030719865:android:b8e41683ad5cacfacd43a8"
    const val API_KEY = "AIzaSyBrtSFaigCPgplvpx3gqWfRvhhLmiHdMdQ"
    const val SENDER_ID = "403030719865"
    const val STORAGE_BUCKET = "skipti-26.firebasestorage.app"

    /** Firebase's auto-created web client: Credential Manager's
     *  serverClientId, the audience Firebase accepts for Google ID tokens. */
    const val WEB_CLIENT_ID = "403030719865-p4a2s68o0rn18rn37h2v4pq110hih40o.apps.googleusercontent.com"

    val options: FirebaseOptions
        get() = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId(APP_ID)
            .setApiKey(API_KEY)
            .setGcmSenderId(SENDER_ID)
            .setStorageBucket(STORAGE_BUCKET)
            .build()

    /** Once, before any Firebase call. Idempotent. */
    fun configure(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options)
    }
}
