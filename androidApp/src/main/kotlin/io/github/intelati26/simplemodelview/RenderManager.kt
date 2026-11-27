package io.github.intelati26.simplemodelview

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class RenderState { Queued, Running, Done, Failed, Cancelled }

/** One export in the manager: a loop video being made (or waiting, or finished). */
data class RenderJob(
    val id: Long, val title: String, val state: RenderState = RenderState.Queued, val done: Int = 0, val total: Int = 0,
    val file: File? = null, val error: String? = null, val mime: String = "video/mp4",
) {
    val active: Boolean get() = state == RenderState.Queued || state == RenderState.Running
}

/**
 * The renders the app is making. They run one at a time, off the page that asked for them, so a loop video carries on when the page is left or the app
 * is put away (a foreground service keeps the process alive and shows the progress in a notification). Finished files wait here to be shared again.
 */
class RenderManager(private val app: Application) {
    val jobs = MutableStateFlow<List<RenderJob>>(emptyList())
    /** A job just finished while the app is on screen: the activity offers to share it, then clears this. */
    val offer = MutableStateFlow<Long?>(null)
    @Volatile var appVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val running = HashMap<Long, Job>()
    private var nextId = 1L
    private val dir = File(app.cacheDir, "renders")

    init { dir.deleteRecursively() }        // what an earlier run left: the list does not outlive the process

    /** Queues [work] (it gets a progress callback of (done, total) and returns the finished file, or null for nothing to make). */
    fun enqueue(title: String, mime: String = "video/mp4", work: suspend ((Int, Int) -> Unit) -> File?): Long {
        val id = synchronized(this) { nextId++ }
        jobs.update { it + RenderJob(id, title, mime = mime) }
        RenderService.start(app)
        val job = scope.launch {
            try {
                gate.withLock {
                    change(id) { it.copy(state = RenderState.Running) }
                    val file = work { d, t -> change(id) { j -> j.copy(done = d, total = t) } }
                    if (file == null) { change(id) { it.copy(state = RenderState.Failed, error = "Nothing to make") }; return@withLock }
                    val kept = File(File(dir, id.toString()).apply { mkdirs() }, file.name)      // a folder per render keeps the file's own name
                    if (!file.renameTo(kept)) { file.copyTo(kept, overwrite = true); file.delete() }      // the page's own folder is emptied by its next export
                    change(id) { it.copy(state = RenderState.Done, file = kept) }
                    if (appVisible) offer.value = id
                }
            } catch (e: CancellationException) {
                change(id) { it.copy(state = RenderState.Cancelled) }
                throw e
            } catch (e: Throwable) {
                change(id) { it.copy(state = RenderState.Failed, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                synchronized(running) { running.remove(id) }
            }
        }
        synchronized(running) { running[id] = job }
        return id
    }

    private fun change(id: Long, f: (RenderJob) -> RenderJob) = jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun cancel(id: Long) { synchronized(running) { running[id] }?.cancel() }

    /** Takes a finished (or failed) job off the list and deletes its file. */
    fun remove(id: Long) {
        val j = jobs.value.firstOrNull { it.id == id } ?: return
        if (j.active) { cancel(id); return }
        j.file?.let { it.delete(); it.parentFile?.delete() }
        jobs.update { l -> l.filter { it.id != id } }
    }

    fun get(id: Long) = jobs.value.firstOrNull { it.id == id }
}

/** The foreground service behind a running render: while it lives the process is kept, and the notification shows how far the queue has got. */
class RenderService : Service() {
    private var watcher: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val manager = AppContainer.renders
        if (intent?.action == ACTION_CANCEL) manager.cancel(intent.getLongExtra(EXTRA_RENDER, -1L))      // the Cancel button of the notification
        ServiceCompat.startForeground(
            this, NOTE_PROGRESS, progressNote(manager.jobs.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (watcher == null) watcher = scope.launch {
            var finishedBefore = manager.jobs.value.count { !it.active }
            manager.jobs.collect { list ->
                if (list.any { it.active }) {
                    notifier().notify(NOTE_PROGRESS, progressNote(list))
                } else {
                    // all done: say so when the app is away, then stop
                    val ready = list.lastOrNull { it.state == RenderState.Done }
                    if (!manager.appVisible && ready != null && list.count { !it.active } > finishedBefore) doneNote(ready)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    private fun notifier() = getSystemService(NotificationManager::class.java)

    private fun open(jobId: Long?): PendingIntent = PendingIntent.getActivity(
        this, (jobId ?: 0L).toInt(), Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply { if (jobId != null) putExtra(EXTRA_RENDER, jobId) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNote(list: List<RenderJob>): Notification {
        val now = list.firstOrNull { it.state == RenderState.Running } ?: list.firstOrNull { it.active }
        val waiting = list.count { it.state == RenderState.Queued }
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(now?.title ?: "Making a video")
            .setContentText((if (now != null && now.total > 0) "Frame ${now.done} of ${now.total}" else "Starting") + if (waiting > 0) " · $waiting waiting" else "")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(null))
        if (now != null && now.total > 0) b.setProgress(now.total, now.done, false) else b.setProgress(0, 0, true)
        if (now != null) b.addAction(0, "Cancel", PendingIntent.getService(this, 1, Intent(this, RenderService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_RENDER, now.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    private fun doneNote(job: RenderJob) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        notifier().notify(NOTE_DONE, NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Ready to share").setContentText(job.title)
            .setAutoCancel(true).setContentIntent(open(job.id)).build())
    }

    companion object {
        const val EXTRA_RENDER = "render"
        private const val ACTION_CANCEL = "cancel"
        private const val CHANNEL_PROGRESS = "renders"
        private const val CHANNEL_DONE = "renders_done"
        private const val NOTE_PROGRESS = 41
        private const val NOTE_DONE = 42

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, RenderService::class.java)) } catch (_: Exception) { /* the render still runs while the app does */ }
        }

        private fun ensureChannels(context: Context) {
            val n = context.getSystemService(NotificationManager::class.java)
            n.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Making videos", NotificationManager.IMPORTANCE_LOW))
            n.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Videos ready", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** Android ends a data-sync service after a long run: the renders stop with it (and say so). */
    override fun onTimeout(startId: Int, fgsType: Int) { AppContainer.renders.jobs.value.filter { it.active }.forEach { AppContainer.renders.cancel(it.id) } }
}
