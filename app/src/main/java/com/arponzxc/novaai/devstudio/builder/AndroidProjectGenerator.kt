package com.arponzxc.novaai.devstudio.builder

import com.arponzxc.novaai.devstudio.model.GeneratedFileSpec
import com.arponzxc.novaai.devstudio.model.GeneratedProjectPlan
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import java.util.Locale

object AndroidProjectGenerator {

    fun generateProjectPlan(
        prompt: String,
        requestedAppName: String? = null,
        requestedPackage: String? = null
    ): GeneratedProjectPlan {
        val cleanName = (requestedAppName?.trim()?.takeIf { it.isNotBlank() } ?: inferAppName(prompt))
            .take(28)
        val pkgSegment = DevSandboxGuard.sanitizeJavaPackageSegment(cleanName)
        val packageName = requestedPackage?.trim()?.takeIf {
            it.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))
        } ?: "com.novastudio.$pkgSegment"
        val packagePath = packageName.replace('.', '/')
        val rootSlug = DevSandboxGuard.sanitizeProjectSlug(cleanName)

        val lower = prompt.lowercase(Locale.ROOT)
        val archetype = when {
            lower.contains("calculat") || lower.contains("math") || lower.contains("ক্যালকুলেটর") -> "CALCULATOR"
            lower.contains("note") || lower.contains("journal") || lower.contains("নোট") -> "NOTES"
            lower.contains("todo") || lower.contains("task") || lower.contains("habit") || lower.contains("টাস্ক") -> "TASKS"
            lower.contains("timer") || lower.contains("pomodoro") || lower.contains("stopwatch") -> "TIMER"
            lower.contains("expense") || lower.contains("budget") || lower.contains("খরচ") -> "EXPENSE"
            else -> "UTILITY"
        }

        val xmlEscapedName = escapeXml(cleanName)
        val mainActivityCode = buildMainActivityCode(packageName, cleanName, prompt, archetype)
        val activityLayoutXml = buildActivityLayoutXml(cleanName, archetype)

        val files = listOf(
            GeneratedFileSpec(
                relativePath = "settings.gradle.kts",
                description = "Gradle settings and repositories",
                content = """
                    pluginManagement {
                        repositories {
                            google()
                            mavenCentral()
                            gradlePluginPortal()
                        }
                    }
                    dependencyResolutionManagement {
                        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                        repositories {
                            google()
                            mavenCentral()
                        }
                    }
                    rootProject.name = "$rootSlug"
                    include(":app")
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "build.gradle.kts",
                description = "Top-level Gradle build configuration",
                content = """
                    plugins {
                        id("com.android.application") version "8.5.2" apply false
                        id("org.jetbrains.kotlin.android") version "1.9.24" apply false
                    }
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "gradle.properties",
                description = "Gradle JVM and AndroidX flags",
                content = """
                    org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                    android.useAndroidX=true
                    kotlin.code.style=official
                    android.nonTransitiveRClass=true
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/build.gradle.kts",
                description = "Android application module build script",
                content = """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "$packageName"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "$packageName"
                            minSdk = 26
                            targetSdk = 34
                            versionCode = 1
                            versionName = "1.0.0"
                        }

                        buildTypes {
                            release {
                                isMinifyEnabled = false
                                proguardFiles(
                                    getDefaultProguardFile("proguard-android-optimize.txt"),
                                    "proguard-rules.pro"
                                )
                            }
                        }

                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_17
                            targetCompatibility = JavaVersion.VERSION_17
                        }
                        kotlinOptions {
                            jvmTarget = "17"
                        }
                    }

                    dependencies {
                        implementation("androidx.core:core-ktx:1.13.1")
                        implementation("androidx.appcompat:appcompat:1.7.0")
                        implementation("com.google.android.material:material:1.12.0")
                        implementation("androidx.constraintlayout:constraintlayout:2.1.4")
                    }
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/proguard-rules.pro",
                description = "ProGuard / R8 keep rules",
                content = "# Project specific ProGuard rules for $cleanName\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/src/main/AndroidManifest.xml",
                description = "Application manifest with MainActivity launcher",
                content = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application
                            android:allowBackup="true"
                            android:label="@string/app_name"
                            android:supportsRtl="true"
                            android:theme="@style/Theme.AppTheme">
                            <activity
                                android:name=".MainActivity"
                                android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/src/main/res/values/strings.xml",
                description = "App strings resource",
                content = """
                    <resources>
                        <string name="app_name">$xmlEscapedName</string>
                    </resources>
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/src/main/res/values/colors.xml",
                description = "App Material colors",
                content = """
                    <resources>
                        <color name="primary">#38BDF8</color>
                        <color name="surface">#0F172A</color>
                        <color name="on_surface">#F8FAFC</color>
                    </resources>
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/src/main/res/values/themes.xml",
                description = "Material 3 DayNight base theme",
                content = """
                    <resources>
                        <style name="Theme.AppTheme" parent="Theme.Material3.DayNight.NoActionBar">
                            <item name="colorPrimary">@color/primary</item>
                            <item name="android:statusBarColor">@color/surface</item>
                        </style>
                    </resources>
                """.trimIndent() + "\n"
            ),
            GeneratedFileSpec(
                relativePath = "app/src/main/res/layout/activity_main.xml",
                description = "Primary Android UI layout",
                content = activityLayoutXml
            ),
            GeneratedFileSpec(
                relativePath = "app/src/main/java/$packagePath/MainActivity.kt",
                description = "Main Kotlin activity implementing $archetype features",
                content = mainActivityCode
            ),
            GeneratedFileSpec(
                relativePath = "README_BUILD.md",
                description = "Build instructions for Termux / Android Studio",
                content = """
                    # $cleanName ($packageName)

                    Generated by NOVA AI Autonomous Development Studio.

                    ## Build APK (Termux / CLI)
                    ```bash
                    gradle assembleDebug
                    ```
                    Output APK: `app/build/outputs/apk/debug/app-debug.apk`
                """.trimIndent() + "\n"
            )
        )

        return GeneratedProjectPlan(
            projectName = cleanName,
            packageName = packageName,
            summary = "Native Kotlin Android starter project ($archetype architecture) ready for editing, validation, and Termux/Gradle build.",
            architectureNotes = listOf(
                "Namespace & Package: $packageName",
                "SDK Targets: minSdk 26 / compileSdk 34 / targetSdk 34",
                "UI Pattern: Material 3 + Edge-to-Edge WindowInsets + Kotlin AppCompat",
                "Module Archetype: $archetype"
            ),
            files = files,
            generatedByAi = false,
            limitationNotice = "Template Fallback Mode: Built from a fixed Android starter template ($archetype). Custom multi-screen logic requires an active Gemini API key."
        )
    }

    private fun inferAppName(prompt: String): String {
        val lower = prompt.lowercase(Locale.ROOT)
        return when {
            lower.contains("calculat") || lower.contains("ক্যালকুলেটর") -> "Nova Calculator"
            lower.contains("note") || lower.contains("নোট") -> "Quick Notes"
            lower.contains("todo") || lower.contains("task") || lower.contains("টাস্ক") -> "Task Flow"
            lower.contains("timer") || lower.contains("pomodoro") -> "Focus Timer"
            lower.contains("expense") || lower.contains("budget") -> "Expense Ledger"
            else -> "Nova Utility"
        }
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun buildActivityLayoutXml(appName: String, archetype: String): String {
        val hintText = when (archetype) {
            "CALCULATOR" -> "Enter expression e.g. 24 * 5 + 12"
            "NOTES" -> "Write a quick note..."
            "TASKS" -> "Add a task item..."
            "TIMER" -> "Enter focus minutes (e.g. 25)"
            "EXPENSE" -> "Title and amount (e.g. Coffee 120)"
            else -> "Enter input or command..."
        }
        val actionLabel = when (archetype) {
            "CALCULATOR" -> "Calculate"
            "NOTES" -> "Save Note"
            "TASKS" -> "Add Task"
            "TIMER" -> "Start / Pause Timer"
            "EXPENSE" -> "Log Expense"
            else -> "Run Action"
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:background="#0B1120"
                android:padding="20dp">

                <TextView
                    android:id="@+id/tvTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="${escapeXml(appName)}"
                    android:textColor="#F8FAFC"
                    android:textSize="24sp"
                    android:textStyle="bold"
                    android:paddingBottom="6dp" />

                <TextView
                    android:id="@+id/tvSubtitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:textColor="#94A3B8"
                    android:textSize="13sp"
                    android:paddingBottom="16dp" />

                <EditText
                    android:id="@+id/etPrimaryInput"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:minHeight="48dp"
                    android:hint="$hintText"
                    android:textColor="#F8FAFC"
                    android:textColorHint="#64748B"
                    android:background="#1E293B"
                    android:padding="14dp"
                    android:layout_marginBottom="12dp" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:layout_marginBottom="16dp">

                    <Button
                        android:id="@+id/btnPrimaryAction"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:minHeight="48dp"
                        android:text="$actionLabel" />

                    <Button
                        android:id="@+id/btnSecondaryAction"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:minHeight="48dp"
                        android:layout_marginStart="8dp"
                        android:text="Clear" />
                </LinearLayout>

                <ScrollView
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_weight="1"
                    android:background="#111827"
                    android:padding="14dp">

                    <TextView
                        android:id="@+id/tvOutputList"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:textColor="#E2E8F0"
                        android:textSize="15sp"
                        android:lineSpacingExtra="6dp" />
                </ScrollView>
            </LinearLayout>
        """.trimIndent() + "\n"
    }

    private fun buildMainActivityCode(
        packageName: String,
        appName: String,
        prompt: String,
        archetype: String
    ): String {
        val safePrompt = prompt.replace("\"", "'").replace("\n", " ").take(120)
        return when (archetype) {
            "CALCULATOR" -> """
                package $packageName

                import android.os.Bundle
                import android.widget.Button
                import android.widget.EditText
                import android.widget.TextView
                import androidx.appcompat.app.AppCompatActivity
                import androidx.core.view.ViewCompat
                import androidx.core.view.WindowInsetsCompat
                import androidx.activity.enableEdgeToEdge

                class MainActivity : AppCompatActivity() {
                    private val history = mutableListOf<String>()

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        enableEdgeToEdge()
                        setContentView(R.layout.activity_main)
                        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
                            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                            insets
                        }

                        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
                        val etInput = findViewById<EditText>(R.id.etPrimaryInput)
                        val btnCalc = findViewById<Button>(R.id.btnPrimaryAction)
                        val btnClear = findViewById<Button>(R.id.btnSecondaryAction)
                        val tvOutput = findViewById<TextView>(R.id.tvOutputList)

                        tvSubtitle.text = "Calculator Engine • Offline Ready"

                        btnCalc.setOnClickListener {
                            val expr = etInput.text.toString().trim()
                            if (expr.isEmpty()) return@setOnClickListener
                            val result = runCatching { evaluateSimple(expr) }
                                .fold(onSuccess = { "= ${'$'}it" }, onFailure = { "Error: ${'$'}{it.message}" })
                            history.add(0, "${'$'}expr  ${'$'}result")
                            tvOutput.text = history.joinToString("\n\n")
                        }

                        btnClear.setOnClickListener {
                            history.clear()
                            etInput.text.clear()
                            tvOutput.text = "Ready for calculations."
                        }
                    }

                    private fun evaluateSimple(expr: String): Double {
                        val tokens = Regex("(\\d+(?:\\.\\d+)?|[+\\-*/])").findAll(expr.replace(" ", ""))
                            .map { it.value }.toList()
                        if (tokens.isEmpty()) error("Invalid expression")
                        var acc = tokens[0].toDouble()
                        var i = 1
                        while (i + 1 < tokens.size) {
                            val op = tokens[i]
                            val rhs = tokens[i + 1].toDouble()
                            acc = when (op) {
                                "+" -> acc + rhs
                                "-" -> acc - rhs
                                "*" -> acc * rhs
                                "/" -> {
                                    if (rhs == 0.0) error("Divide by zero")
                                    acc / rhs
                                }
                                else -> error("Unsupported op: ${'$'}op")
                            }
                            i += 2
                        }
                        return acc
                    }
                }
            """.trimIndent() + "\n"

            else -> """
                package $packageName

                import android.content.Context
                import android.os.Bundle
                import android.widget.Button
                import android.widget.EditText
                import android.widget.TextView
                import androidx.activity.enableEdgeToEdge
                import androidx.appcompat.app.AppCompatActivity
                import androidx.core.view.ViewCompat
                import androidx.core.view.WindowInsetsCompat
                import org.json.JSONArray

                class MainActivity : AppCompatActivity() {
                    private val items = mutableListOf<String>()

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        enableEdgeToEdge()
                        setContentView(R.layout.activity_main)
                        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
                            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                            insets
                        }

                        val prefs = getSharedPreferences("nova_app_store", Context.MODE_PRIVATE)
                        val saved = prefs.getString("items", "[]") ?: "[]"
                        runCatching {
                            val arr = JSONArray(saved)
                            for (i in 0 until arr.length()) items.add(arr.getString(i))
                        }

                        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
                        val etInput = findViewById<EditText>(R.id.etPrimaryInput)
                        val btnAdd = findViewById<Button>(R.id.btnPrimaryAction)
                        val btnClear = findViewById<Button>(R.id.btnSecondaryAction)
                        val tvOutput = findViewById<TextView>(R.id.tvOutputList)

                        tvSubtitle.text = "Goal: $safePrompt"
                        fun render() {
                            tvOutput.text = if (items.isEmpty()) {
                                "No entries yet. Use the input above to add items."
                            } else {
                                items.mapIndexed { idx, s -> "${'$'}{idx + 1}. ${'$'}s" }.joinToString("\n\n")
                            }
                            prefs.edit().putString("items", JSONArray(items).toString()).apply()
                        }
                        render()

                        btnAdd.setOnClickListener {
                            val text = etInput.text.toString().trim()
                            if (text.isNotEmpty()) {
                                items.add(0, text)
                                etInput.text.clear()
                                render()
                            }
                        }

                        btnClear.setOnClickListener {
                            items.clear()
                            render()
                        }
                    }
                }
            """.trimIndent() + "\n"
        }
    }
}
