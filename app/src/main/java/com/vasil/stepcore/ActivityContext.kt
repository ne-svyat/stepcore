package com.vasil.stepcore

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

internal object ActivityContextContract {
    const val PREFS = "stepcore_activity_context"

    const val KEY_RAW_TYPE = "raw_type"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_EVENT_RT = "event_rt"
    const val KEY_CANDIDATE = "candidate_family"
    const val KEY_CANDIDATE_SINCE = "candidate_since"
    const val KEY_STABLE = "stable_family"

    const val FAMILY_UNKNOWN = 0
    const val FAMILY_LOCOMOTION = 1
    const val FAMILY_BLOCK = 2

    const val CONFIDENCE_MIN = 60
    const val BLOCK_STABILITY_MS = 12_000L
    const val SAMPLE_INTERVAL_MS = 15_000L

    fun familyFor(type: Int, confidence: Int): Int {
        if (confidence < CONFIDENCE_MIN) return FAMILY_UNKNOWN
        return when (type) {
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_FOOT -> FAMILY_LOCOMOTION

            DetectedActivity.STILL,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE -> FAMILY_BLOCK

            else -> FAMILY_UNKNOWN
        }
    }

    fun typeName(type: Int): String = when (type) {
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.TILTING -> "TILTING"
        DetectedActivity.UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$type"
    }

    fun familyName(family: Int): String = when (family) {
        FAMILY_LOCOMOTION -> "LOCOMOTION"
        FAMILY_BLOCK -> "BLOCK"
        else -> "UNKNOWN"
    }
}

class ActivityContextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val top = result.mostProbableActivity

        val now = SystemClock.elapsedRealtime()
        val type = top.type
        val confidence = top.confidence
        val family = ActivityContextContract.familyFor(type, confidence)

        val p = context.getSharedPreferences(
            ActivityContextContract.PREFS,
            Context.MODE_PRIVATE
        )

        var candidate = p.getInt(
            ActivityContextContract.KEY_CANDIDATE,
            ActivityContextContract.FAMILY_UNKNOWN
        )
        var candidateSince = p.getLong(
            ActivityContextContract.KEY_CANDIDATE_SINCE,
            0L
        )
        var stable = p.getInt(
            ActivityContextContract.KEY_STABLE,
            ActivityContextContract.FAMILY_UNKNOWN
        )

        when (family) {
            ActivityContextContract.FAMILY_LOCOMOTION -> {
                candidate = family
                candidateSince = now
                stable = family
            }

            ActivityContextContract.FAMILY_UNKNOWN -> {
                candidate = family
                candidateSince = now
                stable = family
            }

            ActivityContextContract.FAMILY_BLOCK -> {
                if (candidate != family) {
                    candidate = family
                    candidateSince = now
                } else if (
                    candidateSince > 0L &&
                    now - candidateSince >= ActivityContextContract.BLOCK_STABILITY_MS
                ) {
                    stable = family
                }
            }
        }

        p.edit()
            .putInt(ActivityContextContract.KEY_RAW_TYPE, type)
            .putInt(ActivityContextContract.KEY_CONFIDENCE, confidence)
            .putLong(ActivityContextContract.KEY_EVENT_RT, now)
            .putInt(ActivityContextContract.KEY_CANDIDATE, candidate)
            .putLong(ActivityContextContract.KEY_CANDIDATE_SINCE, candidateSince)
            .putInt(ActivityContextContract.KEY_STABLE, stable)
            .apply()
    }
}

internal data class ActivityContextSnapshot(
    val rawType: Int,
    val confidence: Int,
    val eventRt: Long,
    val stableFamily: Int
) {
    val rawName: String
        get() = ActivityContextContract.typeName(rawType)

    val stableName: String
        get() = ActivityContextContract.familyName(stableFamily)

    fun compact(nowRt: Long): String {
        val age = if (eventRt > 0L) (nowRt - eventRt).coerceAtLeast(0L) else -1L
        return rawName + " " + confidence + "% · " + stableName +
            " · age=" + (if (age >= 0L) age.toString() + "мс" else "—")
    }
}

internal class ActivityContextTracker(private val context: Context) {
    private val client by lazy { ActivityRecognition.getClient(context) }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ActivityContextReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            437,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun start(log: (String) -> Unit) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            log("[контекст] Activity Guard fail-open: нет ACTIVITY_RECOGNITION permission")
            return
        }

        val gms = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (gms != ConnectionResult.SUCCESS) {
            log("[контекст] Activity Guard fail-open: Google Play services недоступен ($gms)")
            return
        }

        runCatching {
            client.requestActivityUpdates(
                ActivityContextContract.SAMPLE_INTERVAL_MS,
                pendingIntent()
            )
                .addOnSuccessListener {
                    log(
                        "[контекст] Activity Guard ON · локальный AR " +
                            ActivityContextContract.SAMPLE_INTERVAL_MS / 1000L +
                            "с · fail-open"
                    )
                }
                .addOnFailureListener { e ->
                    log(
                        "[контекст] Activity Guard fail-open: " +
                            (e.message ?: e.javaClass.simpleName)
                    )
                }
        }.onFailure { e ->
            log(
                "[контекст] Activity Guard fail-open: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    fun stop() {
        runCatching {
            client.removeActivityUpdates(pendingIntent())
        }
    }

    fun snapshot(): ActivityContextSnapshot {
        val p = context.getSharedPreferences(
            ActivityContextContract.PREFS,
            Context.MODE_PRIVATE
        )
        return ActivityContextSnapshot(
            rawType = p.getInt(
                ActivityContextContract.KEY_RAW_TYPE,
                DetectedActivity.UNKNOWN
            ),
            confidence = p.getInt(
                ActivityContextContract.KEY_CONFIDENCE,
                0
            ),
            eventRt = p.getLong(
                ActivityContextContract.KEY_EVENT_RT,
                0L
            ),
            stableFamily = p.getInt(
                ActivityContextContract.KEY_STABLE,
                ActivityContextContract.FAMILY_UNKNOWN
            )
        )
    }
}

internal class ActivityGuard {
    data class Decision(
        val release: Int,
        val discarded: Int,
        val held: Int,
        val message: String?
    )

    private var heldSteps = 0
    private var heldSinceRt = 0L
    private var heldContextEventRt = 0L

    private val failOpenTimeoutMs = 30_000L

    fun onChip(
        s: ActivityContextSnapshot,
        delta: Int,
        nowRt: Long
    ): Decision {
        if (delta <= 0) return Decision(0, 0, heldSteps, null)

        val contextFresh =
            s.eventRt > 0L && nowRt - s.eventRt <= failOpenTimeoutMs

        if (
            !contextFresh ||
            s.stableFamily != ActivityContextContract.FAMILY_BLOCK
        ) {
            val oldHeld = heldSteps
            val release = oldHeld + delta
            heldSteps = 0
            heldSinceRt = 0L
            heldContextEventRt = 0L

            val msg = if (oldHeld > 0) {
                "RELEASE $oldHeld+$delta · " + s.compact(nowRt)
            } else if (!contextFresh && s.eventRt > 0L) {
                "PASS +$delta · context протух, fail-open"
            } else {
                null
            }
            return Decision(release, 0, 0, msg)
        }

        if (heldSteps <= 0) {
            heldSteps = delta
            heldSinceRt = nowRt
            heldContextEventRt = s.eventRt
            return Decision(
                0, 0, heldSteps,
                "HOLD +$delta · " + s.compact(nowRt)
            )
        }

        if (s.eventRt > heldContextEventRt) {
            val dropped = heldSteps
            heldSteps = delta
            heldSinceRt = nowRt
            heldContextEventRt = s.eventRt
            return Decision(
                0, dropped, heldSteps,
                "DROP $dropped · block подтверждён; HOLD +$delta · " +
                    s.compact(nowRt)
            )
        }

        if (
            heldSinceRt > 0L &&
            nowRt - heldSinceRt >= failOpenTimeoutMs
        ) {
            val release = heldSteps + delta
            val oldHeld = heldSteps
            heldSteps = 0
            heldSinceRt = 0L
            heldContextEventRt = 0L
            return Decision(
                release, 0, 0,
                "RELEASE $oldHeld+$delta · timeout, fail-open"
            )
        }

        heldSteps += delta
        return Decision(
            0, 0, heldSteps,
            "HOLD +$delta · всего $heldSteps · " + s.compact(nowRt)
        )
    }

    fun pendingSteps(): Int = heldSteps
}
