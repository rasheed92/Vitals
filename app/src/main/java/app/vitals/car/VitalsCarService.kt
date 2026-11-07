package app.vitals.car

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vitals.ObdViewModel

/**
 * Android Auto entry point. Declared as a navigation app only because that category is
 * the one that gets a free drawing surface; the dashboard is drawn by [DashRenderer].
 * Sideloaded builds need "Unknown sources" in Android Auto's developer settings.
 */
class VitalsCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            ObdViewModel.get(carContext).ensureAutoConnect()
            return DashScreen(carContext)
        }
    }
}

class DashScreen(ctx: CarContext) : Screen(ctx) {
    private val engine = ObdViewModel.get(ctx)
    private val renderer = DashRenderer(ctx, engine)
    private val handler = Handler(Looper.getMainLooper())
    private var container: SurfaceContainer? = null
    private var visible = Rect()

    private val frame = object : Runnable {
        override fun run() {
            draw()
            handler.postDelayed(this, 100) // 10 fps: smooth enough for gauges, light on the head unit
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(c: SurfaceContainer) {
            container = c
            visible = Rect(0, 0, c.width, c.height)
            draw()
        }

        override fun onSurfaceDestroyed(c: SurfaceContainer) {
            container = null
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visible = Rect(area)
            draw()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onStart(owner: LifecycleOwner) {
                handler.post(frame)
            }

            override fun onStop(owner: LifecycleOwner) {
                handler.removeCallbacks(frame)
            }
        })
    }

    private fun draw() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid) return
        val canvas = try { surface.lockCanvas(null) } catch (e: Exception) { return }
        try {
            renderer.draw(canvas, c.width, c.height, if (visible.isEmpty) Rect(0, 0, c.width, c.height) else visible)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("تصفير").setOnClickListener { engine.resetMinMax() }.build(),
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
