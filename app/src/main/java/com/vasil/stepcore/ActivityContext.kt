package com.vasil.stepcore

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

internal object ActivityContextContract {
    const val PREFS = "stepcore_activity_context"

    const val KEY_RAW_TYPE = "raw_type"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_EVENT_RT = "event_rt"
    const val KEY_STATE_SINCE_RT = "state_since_rt"
    const val KEY_STABLE = "stable_family"
    const val KEY_SOURCE = "source"
    const val KEY_SEQ = "seq"
    const val KEY_TRANSITION_SEEN = "transition_seen"

    // v442. Fresh Sampling snapshot is independent from Transition state.
    // It is used only for WALK/RUN subtype, never for quantity veto.
    const val KEY_SAMPLE_RAW_TYPE = "sample_raw_type"
    const val KEY_SAMPLE_CONFIDENCE = "sample_confidence"
    const val KEY_SAMPLE_RT = "sample_rt"

    const val FAMILY_UNKNOWN = 0
    const val FAMILY_LOCOMOTION = 1
    const val FAMILY_BLOCK = 2

    const val SOURCE_NONE = "NONE"
    const val SOURCE_SEED = "SEED"
    const val SOURCE_TRANSITION = "TRANSITION"
    const val SOURCE_TRANSITION_EXIT = "TRANSITION_EXIT"

    const val CONFIDENCE_MIN = 60
    const val SEED_MAX_AGE_MS = 120_000L

    const val ACTION_TRANSITION =
        "com.vasil.stepcore.activity_context.TRANSITION"
    const val ACTION_SEED =
        "com.vasil.stepcore.activity_context.SEED"

    fun familyFor(type: Int, confidence: Int = 100): Int {
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

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putInt(KEY_RAW_TYPE, DetectedActivity.UNKNOWN)
            .putInt(KEY_CONFIDENCE, 0)
            .putLong(KEY_EVENT_RT, 0L)
            .putLong(KEY_STATE_SINCE_RT, 0L)
            .putInt(KEY_STABLE, FAMILY_UNKNOWN)
            .putString(KEY_SOURCE, SOURCE_NONE)
            .putLong(KEY_SEQ, 0L)
            .putBoolean(KEY_TRANSITION_SEEN, false)
            .putInt(KEY_SAMPLE_RAW_TYPE, DetectedActivity.UNKNOWN)
            .putInt(KEY_SAMPLE_CONFIDENCE, 0)
            .putLong(KEY_SAMPLE_RT, 0L)
            .apply()
    }

    fun saveSample(
        context: Context,
        type: Int,
        confidence: Int,
        eventRt: Long
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SAMPLE_RAW_TYPE, type)
            .putInt(KEY_SAMPLE_CONFIDENCE, confidence)
            .putLong(KEY_SAMPLE_RT, eventRt)
            .apply()
    }

    fun transitionPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            4381,
            Intent(context, ActivityContextReceiver::class.java)
                .setAction(ACTION_TRANSITION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    fun seedPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            4382,
            Intent(context, ActivityContextReceiver::class.java)
                .setAction(ACTION_SEED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    fun save(
        context: Context,
        type: Int,
        confidence: Int,
        family: Int,
        eventRt: Long,
        stateSinceRt: Long,
        source: String,
        transitionSeen: Boolean
    ) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldRt = p.getLong(KEY_EVENT_RT, 0L)
        if (oldRt > 0L && eventRt + 500L < oldRt) return

        val seq = p.getLong(KEY_SEQ, 0L) + 1L
        p.edit()
            .putInt(KEY_RAW_TYPE, type)
            .putInt(KEY_CONFIDENCE, confidence)
            .putLong(KEY_EVENT_RT, eventRt)
            .putLong(KEY_STATE_SINCE_RT, stateSinceRt)
            .putInt(KEY_STABLE, family)
            .putString(KEY_SOURCE, source)
            .putLong(KEY_SEQ, seq)
            .putBoolean(
                KEY_TRANSITION_SEEN,
                transitionSeen || p.getBoolean(KEY_TRANSITION_SEEN, false)
            )
            .apply()
    }
}

class ActivityContextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            ActivityTransitionResult.hasResult(intent) ->
                onTransition(context, intent)
            ActivityRecognitionResult.hasResult(intent) ->
                onSeed(context, intent)
        }
    }

    private fun onTransition(context: Context, intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val p = context.getSharedPreferences(
            ActivityContextContract.PREFS,
            Context.MODE_PRIVATE
        )

        for (e in result.transitionEvents) {
            val rt = e.elapsedRealTimeNanos / 1_000_000L

            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                ActivityContextContract.save(
                    context,
                    e.activityType,
                    100,
                    ActivityContextContract.familyFor(e.activityType),
                    rt,
                    rt,
                    ActivityContextContract.SOURCE_TRANSITION,
                    true
                )
            } else if (
                e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
            ) {
                val currentType = p.getInt(
                    ActivityContextContract.KEY_RAW_TYPE,
                    DetectedActivity.UNKNOWN
                )
                val currentRt = p.getLong(
                    ActivityContextContract.KEY_EVENT_RT,
                    0L
                )
                if (currentType == e.activityType && rt >= currentRt) {
                    ActivityContextContract.save(
                        context,
                        DetectedActivity.UNKNOWN,
                        0,
                        ActivityContextContract.FAMILY_UNKNOWN,
                        rt,
                        rt,
                        ActivityContextContract.SOURCE_TRANSITION_EXIT,
                        true
                    )
                }
            }
        }
    }

    private fun onSeed(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val p = context.getSharedPreferences(
            ActivityContextContract.PREFS,
            Context.MODE_PRIVATE
        )
        val top = result.mostProbableActivity

        // v442. Activity Recognition may report hierarchical activities:
        // ON_FOOT can be the top activity while RUNNING/WALKING is also
        // present with useful confidence. For semantic WALK/RUN subtype,
        // prefer the best specific locomotion activity when confidence is
        // strong enough. The coarse guard seed still uses `top`.
        val specificLocomotion =
            result.probableActivities
                .filter {
                    it.type == DetectedActivity.RUNNING ||
                        it.type == DetectedActivity.WALKING
                }
                .maxByOrNull { it.confidence }

        val semantic =
            if (
                specificLocomotion != null &&
                specificLocomotion.confidence >=
                    HybridMotionFusion.SAMPLE_CONFIDENCE_MIN
            ) specificLocomotion
            else top

        // Fresh one-shot sample is only a semantic witness.
        ActivityContextContract.saveSample(
            context,
            semantic.type,
            semantic.confidence,
            result.elapsedRealtimeMillis
        )

        if (!p.getBoolean(ActivityContextContract.KEY_TRANSITION_SEEN, false)) {
            ActivityContextContract.save(
                context,
                top.type,
                top.confidence,
                ActivityContextContract.familyFor(top.type, top.confidence),
                result.elapsedRealtimeMillis,
                result.elapsedRealtimeMillis,
                ActivityContextContract.SOURCE_SEED,
                false
            )
        }

        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(
                    ActivityContextContract.seedPendingIntent(context)
                )
        }
    }
}

internal data class ActivityContextSnapshot(
    val rawType: Int,
    val confidence: Int,
    val eventRt: Long,
    val stateSinceRt: Long,
    val stableFamily: Int,
    val source: String,
    val seq: Long,
    val sampleRawType: Int,
    val sampleConfidence: Int,
    val sampleRt: Long
) {
    fun usable(nowRt: Long): Boolean = when (source) {
        ActivityContextContract.SOURCE_TRANSITION,
        ActivityContextContract.SOURCE_TRANSITION_EXIT -> true

        ActivityContextContract.SOURCE_SEED ->
            eventRt > 0L &&
                nowRt - eventRt <= ActivityContextContract.SEED_MAX_AGE_MS

        else -> false
    }

    fun stateAge(nowRt: Long): Long =
        if (stateSinceRt > 0L)
            (nowRt - stateSinceRt).coerceAtLeast(0L)
        else -1L

    fun compact(nowRt: Long): String {
        val eventAge = if (eventRt > 0L)
            (nowRt - eventRt).coerceAtLeast(0L)
        else -1L
        val conf = if (source == ActivityContextContract.SOURCE_SEED)
            confidence.toString() + "%"
        else "filtered"
        val stateAgeMs = stateAge(nowRt)

        return ActivityContextContract.typeName(rawType) + " " + conf +
            " · " + ActivityContextContract.familyName(stableFamily) +
            " · src=" + source +
            " · state=" +
            (if (stateAgeMs >= 0L) stateAgeMs.toString() + "мс" else "—") +
            " · eventAge=" +
            (if (eventAge >= 0L) eventAge.toString() + "мс" else "—") +
            " · seq=" + seq
    }
}

internal class ActivityContextTracker(private val context: Context) {
    private val client by lazy { ActivityRecognition.getClient(context) }

    private fun havePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

    private fun gmsOk(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    private fun transitionRequest(): ActivityTransitionRequest {
        val types = intArrayOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.STILL
        )
        val transitions = ArrayList<ActivityTransition>()
        for (type in types) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(type)
                    .setActivityTransition(
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    )
                    .build()
            )
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(type)
                    .setActivityTransition(
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT
                    )
                    .build()
            )
        }
        return ActivityTransitionRequest(transitions)
    }

    fun start(log: (String) -> Unit) {
        ActivityContextContract.reset(context)

        if (!havePermission()) {
            log("[контекст] Transition Guard fail-open: нет ACTIVITY_RECOGNITION")
            return
        }
        if (!gmsOk()) {
            log("[контекст] Transition Guard fail-open: Google Play services недоступен")
            return
        }

        runCatching {
            client.requestActivityTransitionUpdates(
                transitionRequest(),
                ActivityContextContract.transitionPendingIntent(context)
            )
                .addOnSuccessListener {
                    log(
                        "[контекст] Transition Guard ON · event-driven · " +
                            "seed=one-shot · fail-open"
                    )
                }
                .addOnFailureListener { e ->
                    log(
                        "[контекст] Transition Guard fail-open: transition " +
                            (e.message ?: e.javaClass.simpleName)
                    )
                }
        }.onFailure { e ->
            log(
                "[контекст] Transition Guard fail-open: transition " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }

        seed(log)
    }

    fun seed(log: (String) -> Unit) {
        if (!havePermission() || !gmsOk()) return

        val p = context.getSharedPreferences(
            ActivityContextContract.PREFS,
            Context.MODE_PRIVATE
        )
        if (p.getBoolean(ActivityContextContract.KEY_TRANSITION_SEEN, false)) {
            return
        }

        runCatching {
            client.requestActivityUpdates(
                0L,
                ActivityContextContract.seedPendingIntent(context)
            ).addOnFailureListener { e ->
                log(
                    "[контекст] seed fail-open: " +
                        (e.message ?: e.javaClass.simpleName)
                )
            }
        }.onFailure { e ->
            log(
                "[контекст] seed fail-open: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    fun stop() {
        runCatching {
            client.removeActivityTransitionUpdates(
                ActivityContextContract.transitionPendingIntent(context)
            )
        }
        runCatching {
            client.removeActivityUpdates(
                ActivityContextContract.seedPendingIntent(context)
            )
        }
    }

    fun snapshot(): ActivityContextSnapshot {
        val p = context.getSharedPreferences(
            ActivityContextContract.PREFS,
            Context.MODE_PRIVATE
        )
        return ActivityContextSnapshot(
            p.getInt(ActivityContextContract.KEY_RAW_TYPE, DetectedActivity.UNKNOWN),
            p.getInt(ActivityContextContract.KEY_CONFIDENCE, 0),
            p.getLong(ActivityContextContract.KEY_EVENT_RT, 0L),
            p.getLong(ActivityContextContract.KEY_STATE_SINCE_RT, 0L),
            p.getInt(
                ActivityContextContract.KEY_STABLE,
                ActivityContextContract.FAMILY_UNKNOWN
            ),
            p.getString(
                ActivityContextContract.KEY_SOURCE,
                ActivityContextContract.SOURCE_NONE
            ) ?: ActivityContextContract.SOURCE_NONE,
            p.getLong(ActivityContextContract.KEY_SEQ, 0L),
            p.getInt(
                ActivityContextContract.KEY_SAMPLE_RAW_TYPE,
                DetectedActivity.UNKNOWN
            ),
            p.getInt(ActivityContextContract.KEY_SAMPLE_CONFIDENCE, 0),
            p.getLong(ActivityContextContract.KEY_SAMPLE_RT, 0L)
        )
    }

    /**
     * v442. On-demand one-shot Sampling fallback for WALK/RUN subtype.
     * It NEVER replaces Transition state used by HybridGuard.
     */
    fun refreshLocomotionSample(log: (String) -> Unit) {
        if (!havePermission() || !gmsOk()) return

        runCatching {
            client.requestActivityUpdates(
                0L,
                ActivityContextContract.seedPendingIntent(context)
            ).addOnFailureListener { e ->
                log(
                    "[контекст] locomotion sample fail-open: " +
                        (e.message ?: e.javaClass.simpleName)
                )
            }
        }.onFailure { e ->
            log(
                "[контекст] locomotion sample fail-open: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }
}

/**
 * v439. Google context -> hint для HybridGuard.
 *
 * STILL сам не имеет права удалить ни одного шага.
 */
internal fun ActivityContextSnapshot.hybridContext(nowRt: Long): HybridContext {
    val mainUsable = usable(nowRt)

    val hint = if (!mainUsable) {
        HybridContextHint.UNKNOWN
    } else when {
        stableFamily == ActivityContextContract.FAMILY_LOCOMOTION ->
            HybridContextHint.LOCOMOTION

        stableFamily == ActivityContextContract.FAMILY_BLOCK &&
            rawType == DetectedActivity.STILL ->
            HybridContextHint.STILL

        stableFamily == ActivityContextContract.FAMILY_BLOCK &&
            (
                rawType == DetectedActivity.IN_VEHICLE ||
                    rawType == DetectedActivity.ON_BICYCLE
            ) ->
            HybridContextHint.TRANSPORT

        else -> HybridContextHint.UNKNOWN
    }

    val transitionLocomotion =
        if (!mainUsable) HybridLocomotionHint.UNKNOWN
        else when (rawType) {
            DetectedActivity.RUNNING -> HybridLocomotionHint.RUNNING
            DetectedActivity.WALKING -> HybridLocomotionHint.WALKING
            DetectedActivity.ON_FOOT -> HybridLocomotionHint.ON_FOOT
            else -> HybridLocomotionHint.UNKNOWN
        }

    val sampleAge =
        if (sampleRt > 0L)
            (nowRt - sampleRt).coerceAtLeast(0L)
        else -1L

    val sampleLocomotion = when (sampleRawType) {
        DetectedActivity.RUNNING -> HybridLocomotionHint.RUNNING
        DetectedActivity.WALKING -> HybridLocomotionHint.WALKING
        DetectedActivity.ON_FOOT -> HybridLocomotionHint.ON_FOOT
        else -> HybridLocomotionHint.UNKNOWN
    }

    return HybridContext(
        hint = hint,
        stateAgeMs = if (mainUsable) stateAge(nowRt) else -1L,
        locomotion = transitionLocomotion,
        sampleLocomotion = sampleLocomotion,
        sampleConfidence = sampleConfidence,
        sampleAgeMs = sampleAge
    )
}
