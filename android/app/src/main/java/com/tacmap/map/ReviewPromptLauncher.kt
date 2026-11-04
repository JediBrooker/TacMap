package com.tacmap.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Hands the ask to Play. The once-per-install flag was already flipped by the
 * caller, so a failure here (no Play on a sideloaded build, quota, whatever)
 * just means we never asked. Fine, thats the deal with this API: it never
 * tells us if the card showed and we must not retry.
 */
internal suspend fun launchPlayReviewFlow(context: Context) {
    val activity = context.findActivityForReview() ?: return
    runCatching {
        val manager = ReviewManagerFactory.create(context.applicationContext)
        val info = manager.requestReview()
        manager.launchReview(activity, info)
    }
}

private tailrec fun Context.findActivityForReview(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityForReview()
    else -> null
}
