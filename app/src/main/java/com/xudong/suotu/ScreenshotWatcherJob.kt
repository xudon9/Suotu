package com.xudong.suotu

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watches for new images and silently saves a shrunk copy.
 *
 * Detection is **event-based, not polling**: a JobScheduler *content trigger* asks the
 * system to wake us when MediaStore's image collection changes, so the app costs
 * nothing while idle and needs no foreground service.
 *
 * Two platform subtleties caused real bugs here and are worth stating:
 *
 *  1. A content-trigger job is **single-shot**. Once it fires it is no longer
 *     registered, so it must be re-armed on *every* exit path — not only in
 *     [onStopJob], which the system calls when it cancels us and never after a normal
 *     `jobFinished()`. Missing that made the watcher fire exactly once and go deaf.
 *
 *  2. The wakeup often carries **no usable triggered URI** — the row may exist before
 *     the file is readable. Bailing out then silently dropped that screenshot, so a
 *     miss falls back to scanning recent media.
 *
 * Which folders/names qualify comes from [Settings.autoShrinkRules], so this is not
 * limited to screenshots — though that stays the default.
 */
class ScreenshotWatcherJob : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val settings = Settings(this)

        // Re-arm FIRST: whatever happens below, the next image must still wake us.
        if (!settings.autoShrinkEnabled) return false
        schedule(this)

        work = CoroutineScope(Dispatchers.IO).launch {
            try {
                val rules = settings.autoShrinkRules
                val handled = params?.triggeredContentUris
                    ?.count { uri -> processUri(uri, rules, settings) }
                    ?: 0

                // Trigger arrived without a usable URI — look for recent matches directly.
                if (handled == 0) {
                    MediaQuery.recentMatching(
                        this@ScreenshotWatcherJob, rules,
                        maxAgeSeconds = RECENT_WINDOW_SECONDS,
                        scanLimit = RECENT_LIMIT,
                    ).forEach { item ->
                        processItem(item.uri, item.displayName, settings)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "auto-shrink failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true // work continues on the coroutine
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        // Already re-armed in onStartJob; true asks for a retry of this cancelled run.
        return true
    }

    /** Resolve a triggered URI and shrink it if the rules accept it. */
    private fun processUri(
        uri: Uri,
        rules: List<WatchRule>,
        settings: Settings,
    ): Boolean {
        val (name, path) = resolveNameAndPath(uri) ?: return false
        if (MediaQuery.isOwnOutput(path, name)) return false
        if (rules.none { it.matches(path, name) }) return false
        return processItem(uri, name, settings)
    }

    /** Shrink and save; assumes the rules already matched. */
    private fun processItem(uri: Uri, name: String, settings: Settings): Boolean {
        if (settings.wasProcessed(name)) return false

        return try {
            // Use the SAME settings as the interactive path.
            //
            // This previously called a preset-based overload, which meant the background
            // watcher silently ignored the width slider, the quality override and every
            // other choice made in the UI: it always produced 720px because that is the
            // default preset. Anything the user sets should apply here too — a setting
            // that only affects one of two paths is worse than no setting.
            val result = ShrinkEngine.shrink(
                context = this,
                uri = uri,
                targetWidth = settings.outputWidth,
                budgetBytes = SizeRange.budgetFor(settings.outputWidth),
                formatPolicy = settings.formatPolicy,
                forcedQuality = settings.manualQuality,
            )
            val saved = MediaStoreSaver.save(this, result, name) ?: return false

            settings.markProcessed(name)
            if (settings.autoShrinkNotify) {
                ShrinkNotifier.notifyReady(this, saved, result)
            }
            Log.i(TAG, "shrank $name -> ${result.outputBytes} bytes")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not shrink $name", e)
            false
        }
    }

    private fun resolveNameAndPath(uri: Uri): Pair<String, String?>? =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: return@use null
                    name to c.getString(1)
                } else null
            }
        }.getOrNull()

    companion object {
        private const val TAG = "SuotuWatcher"
        private const val JOB_ID = 4711

        /** Bounds for the fallback scan when the trigger names no file. */
        private const val RECENT_WINDOW_SECONDS = 5 * 60L
        private const val RECENT_LIMIT = 5

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, ScreenshotWatcherJob::class.java)
            )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                // React as fast as the platform allows: the update delay restarts on
                // every further change, so a large value stacks into seconds of lag.
                .setTriggerContentUpdateDelay(0L)
                .setTriggerContentMaxDelay(1_000L)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** True if the watcher is currently registered with the system. */
        fun isScheduled(context: Context): Boolean =
            context.getSystemService(JobScheduler::class.java)
                ?.allPendingJobs?.any { it.id == JOB_ID } == true
    }
}
