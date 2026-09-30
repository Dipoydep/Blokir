package com.kontrolortu.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.UUID

// ---------- Aplikasi + Firebase ----------
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey("AIzaSyBD0bJ-VZItWfS95ofGmiNH6Ee2zNe1av8")
                    .setApplicationId("1:313136750773:web:6d30a26212137be5735efc")
                    .setDatabaseUrl("https://nweacsess-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .setProjectId("nweacsess")
                    .build()
            )
        }
        Sync.start(this)
    }
}

// ---------- Sinkronisasi dengan panel web ----------
object Sync {
    private var started = false
    private lateinit var base: DatabaseReference
    private lateinit var prefs: SharedPreferences

    @Volatile var blocked: Set<String> = emptySet()
    @Volatile var running = false
    @Volatile var protect = false
    @Volatile var html = ""

    // Firebase key tidak boleh ada titik
    fun key(pkg: String) = pkg.replace(".", "_")

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        prefs = ctx.getSharedPreferences("k", Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: return
        if (!prefs.getBoolean("paired", false)) return
        started = true
        val app = ctx.applicationContext

        blocked = prefs.getStringSet("blocked", emptySet())!!.toSet()
        running = prefs.getBoolean("running", false)
        protect = prefs.getBoolean("protect", false)
        html = prefs.getString("html", "") ?: ""

        base = FirebaseDatabase.getInstance().getReference("devices/$id")

        listen(base.child("settings/is_running")) {
            running = it.getValue(Boolean::class.java) == true
            prefs.edit().putBoolean("running", running).apply()
        }
        listen(base.child("settings/protection_enabled")) {
            protect = it.getValue(Boolean::class.java) == true
            prefs.edit().putBoolean("protect", protect).apply()
        }
        listen(base.child("settings/custom_lock_html")) {
            html = it.getValue(String::class.java) ?: ""
            prefs.edit().putString("html", html).apply()
        }
        listen(base.child("installed_apps")) { s ->
            val set = HashSet<String>()
            s.children.forEach { c ->
                if (c.child("blocked").getValue(Boolean::class.java) == true) {
                    c.child("packageName").getValue(String::class.java)?.let { set.add(it) }
                }
            }
            blocked = set
            prefs.edit().putStringSet("blocked", set).apply()
        }

        pushApps(app)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, f)
        }
    }

    private fun listen(ref: DatabaseReference, f: (DataSnapshot) -> Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) = f(s)
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (i.action == Intent.ACTION_PACKAGE_REMOVED) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                base.child("installed_apps/${key(pkg)}").removeValue()
            } else {
                pushApps(c.applicationContext)
            }
        }
    }

    // Kirim semua aplikasi terpasang (yang punya ikon launcher) ke panel
    private fun pushApps(ctx: Context) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
        val ref = base.child("installed_apps")
        ref.get().addOnSuccessListener { snap ->
            val keep = apps.map { key(it.first) }.toSet()
            val upd = HashMap<String, Any?>()
            snap.children.forEach { c ->
                val k = c.key ?: return@forEach
                if (!keep.contains(k)) upd[k] = null
            }
            apps.forEach { (p, n) ->
                upd["${key(p)}/appName"] = n
                upd["${key(p)}/packageName"] = p
            }
            ref.updateChildren(upd)
        }
    }
}

// ---------- Layar putih + kode pairing ----------
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var codeView: TextView
    private lateinit var btnAcc: Button
    private lateinit var btnAdmin: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("k", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 48, 48, 48)
        }
        codeView = TextView(this).apply {
            textSize = 40f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        btnAcc = Button(this).apply {
            text = "1. Aktifkan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        btnAdmin = Button(this).apply {
            text = "2. Aktifkan Device Admin"
            setOnClickListener {
                startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@MainActivity, AdminReceiver::class.java)
                    )
                )
            }
        }
        root.addView(codeView)
        root.addView(btnAcc)
        root.addView(btnAdmin)
        setContentView(root)

        startPairing()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val paired = prefs.getBoolean("paired", false)
        codeView.text = if (paired) "" else (prefs.getString("code", "") ?: "")
        val acc = (Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").contains(packageName)
        val adm = getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(this, AdminReceiver::class.java))
        btnAcc.visibility = if (acc) View.GONE else View.VISIBLE
        btnAdmin.visibility = if (adm) View.GONE else View.VISIBLE
    }

    private fun startPairing() {
        val id = prefs.getString("deviceId", null)
            ?: UUID.randomUUID().toString().replace("-", "").take(20)
                .also { prefs.edit().putString("deviceId", it).apply() }

        if (prefs.getBoolean("paired", false)) {
            Sync.start(applicationContext)
            return
        }

        val code = prefs.getString("code", null)
            ?: (100000 + java.util.Random().nextInt(900000)).toString()
                .also { prefs.edit().putString("code", it).apply() }

        val ref = FirebaseDatabase.getInstance().getReference("pending_pairings/$code")
        ref.setValue(mapOf("deviceId" to id, "status" to "PENDING"))
        ref.child("status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.getValue(String::class.java) == "SUCCESS") {
                    s.ref.removeEventListener(this)
                    prefs.edit().putBoolean("paired", true).apply()
                    ref.removeValue()
                    Sync.start(applicationContext)
                    runOnUiThread { refresh() }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
}

// ---------- Layar blokir (HTML dari panel) ----------
class BlockActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val w = WebView(this)
        w.settings.javaScriptEnabled = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.setBackgroundColor(Color.WHITE)
        val body = Sync.html.ifBlank {
            "<h1 style='text-align:center;margin-top:30vh'>Aplikasi ini diblokir</h1>"
        }
        w.loadDataWithBaseURL(
            null,
            "<meta name='viewport' content='width=device-width,initial-scale=1'>$body",
            "text/html", "UTF-8", null
        )
        setContentView(w)
    }

    override fun onBackPressed() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

// ---------- Penjaga: blokir app + lindungi APK ----------
class GuardService : AccessibilityService() {
    private var last = 0L

    private val guarded = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Sync.start(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val ev = e ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Protect APK: tutup halaman pengaturan yang menampilkan app ini
        if (Sync.protect && pkg in guarded) {
            val root = rootInActiveWindow
            if (root != null && root.findAccessibilityNodeInfosByText("Kontrol Ortu").isNotEmpty()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // Blokir aplikasi yang dipilih di panel
        if (Sync.running &&
            ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg in Sync.blocked
        ) {
            val now = System.currentTimeMillis()
            if (now - last < 1000) return
            last = now
            startActivity(
                Intent(this, BlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    override fun onInterrupt() {}
}

class AdminReceiver : DeviceAdminReceiver()
