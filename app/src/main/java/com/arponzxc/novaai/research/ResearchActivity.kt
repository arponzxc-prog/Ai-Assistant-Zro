package com.arponzxc.novaai.research

import com.arponzxc.novaai.ui.theme.NovaUi
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ResearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val initialQuery = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
        val autoRun = intent?.getBooleanExtra(EXTRA_AUTO_RUN, false) == true

        setContent {
            NovaPageTheme {
                ResearchStudioScreen(
                    initialQuery = initialQuery,
                    autoRun = autoRun,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_QUERY = "extra_research_query"
        const val EXTRA_AUTO_RUN = "extra_research_auto_run"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchStudioScreen(
    initialQuery: String,
    autoRun: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val engine = remember { ResearchEngine(context) }

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Explore, 1 = History
    var query by remember { mutableStateOf(initialQuery) }
    var selectedMode by remember { mutableStateOf(ResearchMode.DETAILED) }
    var isSearching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ResearchResult?>(null) }
    var history by remember { mutableStateOf(engine.getHistory()) }

    fun executeSearch(q: String = query, mode: ResearchMode = selectedMode) {
        if (q.isBlank()) return
        scope.launch {
            isSearching = true
            activeTab = 0
            val res = engine.performResearch(q, mode.label)
            result = res
            history = engine.getHistory()
            isSearching = false
        }
    }

    LaunchedEffect(autoRun, initialQuery) {
        if (autoRun && initialQuery.isNotBlank()) {
            executeSearch(initialQuery, selectedMode)
        }
    }

    Surface(
        color = NovaUi.bg,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top Bar
                NovaPageHeader(
                    title = "AI Knowledge & Research",
                    subtitle = "Wikipedia • DuckDuckGo • Wikidata • Multi-Provider Synthesis",
                    onBack = onBack,
                    backTestTag = "research_back_button"
                )

                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = NovaUi.cardSolid,
                    modifier = Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Research") },
                        icon = { Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = {
                            history = engine.getHistory()
                            activeTab = 1
                        },
                        text = { Text("History (${history.size})") },
                        icon = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                if (activeTab == 0) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Enter research topic or question…") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("research_query_input")
                            )
                            Button(
                                onClick = { executeSearch() },
                                enabled = !isSearching && query.isNotBlank(),
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("research_search_button")
                            ) {
                                if (isSearching) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NovaUi.onAccent)
                                } else {
                                    Icon(Icons.Rounded.Search, contentDescription = "Run Research")
                                }
                            }
                        }

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResearchMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = selectedMode == mode,
                                    onClick = { selectedMode = mode },
                                    label = { Text(mode.label, fontSize = 12.sp) }
                                )
                            }
                        }

                        result?.let { res ->
                            if (!res.success) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NovaUi.errorBg),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = NovaUi.error)
                                            Text("Research Unavailable", fontWeight = FontWeight.Bold, color = NovaUi.textPrimary)
                                        }
                                        Text(res.errorMessage, color = NovaUi.error, fontSize = 13.sp)
                                        if (res.providerDiagnostics.isNotEmpty()) {
                                            Text(
                                                text = res.providerDiagnostics.joinToString(" • "),
                                                color = NovaUi.error,
                                                fontSize = 11.sp
                                            )
                                        }
                                        OutlinedButton(onClick = { executeSearch() }) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            } else {
                                // AI Summary Card
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = NovaUi.accent, modifier = Modifier.size(18.dp))
                                                Text(
                                                    text = if (res.aiSynthesized) "Verified Multi-Source Synthesis" else "Verified Source Report",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = NovaUi.accent
                                                )
                                            }
                                            IconButton(onClick = {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("NOVA Research", res.summary))
                                                Toast.makeText(context, "Research report copied", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy report", tint = NovaUi.textSecondary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Text(
                                            text = res.summary,
                                            fontSize = 13.5.sp,
                                            lineHeight = 20.sp,
                                            color = NovaUi.textPrimary
                                        )
                                        if (res.providerDiagnostics.isNotEmpty()) {
                                            Text(
                                                text = res.providerDiagnostics.joinToString(" | "),
                                                fontSize = 10.5.sp,
                                                color = NovaUi.textSecondary
                                            )
                                        }
                                    }
                                }

                                // Sources List
                                Text(
                                    text = "Verified Sources (${res.sources.size})",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = NovaUi.textSecondary
                                )
                                res.sources.forEachIndexed { idx, src ->
                                    val badgeColor = when (src.credibility) {
                                        SourceCredibility.HIGH -> NovaUi.successBg
                                        SourceCredibility.MEDIUM -> NovaUi.accentSoft
                                        SourceCredibility.LOW -> NovaUi.border
                                    }
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                runCatching { uriHandler.openUri(src.url) }
                                                    .onFailure { Toast.makeText(context, "Cannot open URL", Toast.LENGTH_SHORT).show() }
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "${idx + 1}. ${src.title}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.5.sp,
                                                    color = NovaUi.textPrimary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(badgeColor)
                                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                                ) {
                                                    Text(src.credibility.label, fontSize = 10.sp, color = NovaUi.textPrimary, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                            Text(src.snippet, fontSize = 12.sp, color = NovaUi.textSecondary)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = NovaUi.accent, modifier = Modifier.size(14.dp))
                                                Text(
                                                    text = "${src.providerName} • ${src.domain.ifBlank { src.url }}",
                                                    fontSize = 11.sp,
                                                    color = NovaUi.accent
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                } else {
                    // Saved History Tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (history.isEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No saved research sessions yet. Run a search to store structured findings here.",
                                    color = NovaUi.textSecondary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(onClick = {
                                    engine.clearHistory()
                                    history = emptyList()
                                }) {
                                    Text("Clear All", color = NovaUi.error)
                                }
                            }
                            history.forEach { item ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            query = item.query
                                            result = item
                                            activeTab = 0
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.query, fontWeight = FontWeight.Bold, color = NovaUi.textPrimary, fontSize = 14.sp)
                                            Text(
                                                text = "${item.mode} • ${item.sources.size} sources • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.timestamp))}",
                                                fontSize = 11.sp,
                                                color = NovaUi.textSecondary
                                            )
                                        }
                                        IconButton(onClick = {
                                            engine.deleteHistoryEntry(item.timestamp)
                                            history = engine.getHistory()
                                        }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete history item", tint = NovaUi.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
