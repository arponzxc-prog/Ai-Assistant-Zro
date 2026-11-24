package com.arponzxc.novaai.devstudio.builder

import com.arponzxc.novaai.devstudio.model.GeneratedFileSpec
import com.arponzxc.novaai.devstudio.model.GeneratedProjectPlan
import java.util.Locale

object WebProjectGenerator {

    fun generateProjectPlan(
        prompt: String,
        requestedSiteName: String? = null
    ): GeneratedProjectPlan {
        val siteName = (requestedSiteName?.trim()?.takeIf { it.isNotBlank() } ?: inferSiteTitle(prompt)).take(40)
        val safePrompt = prompt.trim().ifBlank { "Modern responsive web application" }
        val lower = safePrompt.lowercase(Locale.ROOT)
        val archetype = when {
            lower.contains("portfolio") || lower.contains("resume") || lower.contains("পোর্টফোলিও") -> "PORTFOLIO"
            lower.contains("todo") || lower.contains("task") || lower.contains("planner") -> "TASK_BOARD"
            lower.contains("shop") || lower.contains("store") || lower.contains("ecommerce") || lower.contains("দোকান") -> "STOREFRONT"
            lower.contains("calculat") || lower.contains("converter") || lower.contains("tool") -> "CALCULATOR"
            else -> "LANDING_DASHBOARD"
        }

        val dataJson = """
            {
              "title": "${escapeJson(siteName)}",
              "archetype": "$archetype",
              "prompt": "${escapeJson(safePrompt)}",
              "metrics": [
                { "label": "Performance", "value": "99/100" },
                { "label": "Responsive", "value": "Mobile + Desktop" },
                { "label": "Architecture", "value": "Modular Vanilla JS" }
              ],
              "items": [
                { "id": 1, "title": "Core Layout Engine", "status": "Active", "detail": "Adaptive Flexbox & CSS Grid layout" },
                { "id": 2, "title": "Interactive State", "status": "Live", "detail": "LocalStorage persistence with zero external CDN failures" },
                { "id": 3, "title": "Theme Switcher", "status": "Ready", "detail": "Instant Dark / Light contrast mode" }
              ]
            }
        """.trimIndent() + "\n"

        val logoSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">
              <defs>
                <linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#38BDF8"/>
                  <stop offset="100%" stop-color="#6366F1"/>
                </linearGradient>
              </defs>
              <rect width="64" height="64" rx="16" fill="#0F172A"/>
              <path d="M18 44L32 16L46 44H37L32 33L27 44H18Z" fill="url(#g)"/>
            </svg>
        """.trimIndent() + "\n"

        val headerComponentJs = """
            export function mountHeader(containerId, siteTitle, activePage) {
              const el = document.getElementById(containerId);
              if (!el) return;
              el.innerHTML = `
                <header class="site-header">
                  <div class="brand">
                    <img src="/assets/logo.svg" alt="Logo" class="brand-logo" />
                    <div>
                      <strong>${escapeHtml(siteName)}</strong>
                      <span class="badge">${archetype}</span>
                    </div>
                  </div>
                  <nav class="nav-links">
                    <a href="/index.html" class="${'$'}{activePage === 'home' ? 'active' : ''}">Home</a>
                    <a href="/pages/about.html" class="${'$'}{activePage === 'about' ? 'active' : ''}">Architecture</a>
                    <button id="themeToggleBtn" class="btn-outline" type="button">Theme</button>
                  </nav>
                </header>
              `;
              const btn = document.getElementById("themeToggleBtn");
              if (btn) {
                btn.addEventListener("click", () => {
                  document.body.classList.toggle("light-mode");
                  localStorage.setItem("nova_web_theme", document.body.classList.contains("light-mode") ? "light" : "dark");
                });
              }
              if (localStorage.getItem("nova_web_theme") === "light") {
                document.body.classList.add("light-mode");
              }
            }
        """.trimIndent() + "\n"

        val indexHtml = buildIndexHtml(siteName, safePrompt, archetype)
        val styleCss = buildStyleCss()
        val scriptJs = buildScriptJs(archetype)
        val aboutHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>Architecture — ${escapeHtml(siteName)}</title>
              <link rel="stylesheet" href="/style.css" />
            </head>
            <body>
              <div id="header-root"></div>
              <main class="container">
                <section class="hero-card">
                  <h1>Project Architecture</h1>
                  <p>Multi-file modular web workspace served cleanly over localhost HTTP preview.</p>
                  <ul>
                    <li><code>index.html</code> — Primary responsive entrypoint</li>
                    <li><code>style.css</code> — CSS custom properties + Light/Dark theme + Grid</li>
                    <li><code>script.js</code> — Interactive client state + <code>localStorage</code></li>
                    <li><code>components/header.js</code> — Shared navigation module</li>
                    <li><code>data/app-data.json</code> — Structured application content</li>
                  </ul>
                  <a class="btn-primary" href="/index.html">Back to App</a>
                </section>
              </main>
              <script type="module">
                import { mountHeader } from '/components/header.js';
                mountHeader('header-root', '${escapeHtml(siteName)}', 'about');
              </script>
            </body>
            </html>
        """.trimIndent() + "\n"

        val files = listOf(
            GeneratedFileSpec("index.html", indexHtml, "Primary responsive HTML5 entrypoint"),
            GeneratedFileSpec("style.css", styleCss, "Responsive dark/light stylesheet"),
            GeneratedFileSpec("script.js", scriptJs, "Interactive JS application logic"),
            GeneratedFileSpec("components/header.js", headerComponentJs, "Reusable header & theme toggle component"),
            GeneratedFileSpec("pages/about.html", aboutHtml, "Secondary architecture & docs page"),
            GeneratedFileSpec("data/app-data.json", dataJson, "Structured JSON dataset"),
            GeneratedFileSpec("assets/logo.svg", logoSvg, "Vector brand emblem")
        )

        return GeneratedProjectPlan(
            projectName = siteName,
            packageName = "web.${siteName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-")}",
            summary = "Responsive multi-file HTML/CSS/JS starter project ($archetype) ready for local HTTP live preview.",
            architectureNotes = listOf(
                "Responsive CSS Grid & mobile-first breakpoints",
                "Zero external CDN dependencies (100% offline/local server ready)",
                "ES Modules + LocalStorage state persistence"
            ),
            files = files,
            generatedByAi = false,
            limitationNotice = "Template Fallback Mode: Built from a fixed web starter template ($archetype). Custom sections and logic require an active Gemini API key."
        )
    }

    private fun inferSiteTitle(prompt: String): String {
        val lower = prompt.lowercase(Locale.ROOT)
        return when {
            lower.contains("portfolio") -> "Creator Portfolio"
            lower.contains("todo") || lower.contains("task") -> "TaskFlow Studio"
            lower.contains("shop") || lower.contains("store") -> "Nova Storefront"
            lower.contains("calculat") -> "Precision Calc Web"
            else -> "Nova Web Studio App"
        }
    }

    private fun buildIndexHtml(siteName: String, prompt: String, archetype: String): String {
        val actionTitle = when (archetype) {
            "TASK_BOARD" -> "Interactive Task Workspace"
            "STOREFRONT" -> "Product Catalog & Cart"
            "CALCULATOR" -> "Live Expression Evaluator"
            "PORTFOLIO" -> "Featured Work & Contact Notes"
            else -> "Interactive Workspace"
        }
        val placeholder = when (archetype) {
            "TASK_BOARD" -> "Add a new task or milestone..."
            "CALCULATOR" -> "Type expression e.g. (125 * 4) + 50"
            else -> "Add item or note to live workspace..."
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>${escapeHtml(siteName)}</title>
              <link rel="stylesheet" href="/style.css" />
            </head>
            <body>
              <div id="header-root"></div>
              <main class="container">
                <section class="hero-card">
                  <span class="eyebrow">NOVA WEB STUDIO • LIVE APP</span>
                  <h1>${escapeHtml(siteName)}</h1>
                  <p class="subtitle">${escapeHtml(prompt)}</p>
                  <div id="metrics-grid" class="metrics-grid"></div>
                </section>

                <section class="panel-card">
                  <h2>$actionTitle</h2>
                  <form id="workspaceForm" class="input-row">
                    <input id="workspaceInput" type="text" placeholder="$placeholder" required />
                    <button type="submit" class="btn-primary">Execute</button>
                  </form>
                  <div id="statusBanner" class="status-banner">Ready • Served from local workspace</div>
                  <ul id="itemsList" class="items-list"></ul>
                </section>
              </main>
              <script type="module" src="/script.js"></script>
            </body>
            </html>
        """.trimIndent() + "\n"
    }

    private fun buildStyleCss(): String = """
        :root {
          --bg: #070B14;
          --card: #111827;
          --border: #1F2937;
          --text: #F8FAFC;
          --muted: #94A3B8;
          --accent: #38BDF8;
          --accent-strong: #6366F1;
        }
        body.light-mode {
          --bg: #F1F5F9;
          --card: #FFFFFF;
          --border: #CBD5E1;
          --text: #0F172A;
          --muted: #475569;
          --accent: #0284C7;
          --accent-strong: #4F46E5;
        }
        * { box-sizing: border-box; }
        body {
          margin: 0;
          font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
          background: var(--bg);
          color: var(--text);
          line-height: 1.5;
        }
        .site-header {
          display: flex;
          flex-wrap: wrap;
          justify-content: space-between;
          align-items: center;
          gap: 12px;
          padding: 14px 20px;
          border-bottom: 1px solid var(--border);
          background: var(--card);
        }
        .brand { display: flex; align-items: center; gap: 10px; }
        .brand-logo { width: 34px; height: 34px; border-radius: 8px; }
        .badge {
          display: inline-block;
          margin-left: 8px;
          padding: 2px 8px;
          font-size: 11px;
          border-radius: 999px;
          background: rgba(56, 189, 248, 0.16);
          color: var(--accent);
        }
        .nav-links { display: flex; align-items: center; gap: 12px; }
        .nav-links a {
          color: var(--muted);
          text-decoration: none;
          font-weight: 600;
          font-size: 14px;
        }
        .nav-links a.active { color: var(--accent); }
        .container { max-width: 960px; margin: 24px auto; padding: 0 16px; display: grid; gap: 18px; }
        .hero-card, .panel-card {
          background: var(--card);
          border: 1px solid var(--border);
          border-radius: 16px;
          padding: 20px;
        }
        .eyebrow { color: var(--accent); font-size: 11px; font-weight: 700; letter-spacing: 0.08em; }
        h1 { margin: 8px 0; font-size: 28px; }
        .subtitle { color: var(--muted); margin-top: 0; }
        .metrics-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
          gap: 12px;
          margin-top: 16px;
        }
        .metric-box {
          padding: 12px;
          border-radius: 12px;
          border: 1px solid var(--border);
          background: rgba(56, 189, 248, 0.05);
        }
        .metric-label { font-size: 12px; color: var(--muted); }
        .metric-val { font-size: 18px; font-weight: 700; color: var(--accent); }
        .input-row { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
        input {
          flex: 1;
          min-width: 180px;
          padding: 12px 14px;
          border-radius: 10px;
          border: 1px solid var(--border);
          background: var(--bg);
          color: var(--text);
          font-size: 15px;
        }
        .btn-primary, .btn-outline {
          padding: 11px 16px;
          border-radius: 10px;
          font-weight: 600;
          cursor: pointer;
          border: none;
          text-decoration: none;
          display: inline-block;
        }
        .btn-primary {
          background: linear-gradient(135deg, var(--accent), var(--accent-strong));
          color: #FFFFFF;
        }
        .btn-outline {
          background: transparent;
          color: var(--text);
          border: 1px solid var(--border);
        }
        .status-banner { font-size: 13px; color: var(--muted); margin-bottom: 12px; }
        .items-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 10px; }
        .item-row {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 12px;
          border-radius: 10px;
          border: 1px solid var(--border);
        }
        .delete-btn {
          background: rgba(239, 68, 68, 0.15);
          color: #F87171;
          border: none;
          border-radius: 8px;
          padding: 6px 10px;
          cursor: pointer;
        }
    """.trimIndent() + "\n"

    private fun buildScriptJs(archetype: String): String = """
        import { mountHeader } from '/components/header.js';

        const STORAGE_KEY = 'nova_web_items_v1';
        const ARCHETYPE = '$archetype';

        mountHeader('header-root', document.title, 'home');

        const metricsRoot = document.getElementById('metrics-grid');
        const itemsList = document.getElementById('itemsList');
        const form = document.getElementById('workspaceForm');
        const input = document.getElementById('workspaceInput');
        const banner = document.getElementById('statusBanner');

        let entries = [];
        try {
          entries = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
        } catch (e) {
          console.error('Failed loading saved entries:', e);
        }

        fetch('/data/app-data.json')
          .then((r) => r.json())
          .then((data) => {
            if (metricsRoot && Array.isArray(data.metrics)) {
              metricsRoot.innerHTML = data.metrics.map((m) => `
                <div class="metric-box">
                  <div class="metric-label">${'$'}{m.label}</div>
                  <div class="metric-val">${'$'}{m.value}</div>
                </div>
              `).join('');
            }
            if (entries.length === 0 && Array.isArray(data.items)) {
              entries = data.items.map((it) => `${'$'}{it.title} — ${'$'}{it.detail}`);
              saveAndRender();
            }
          })
          .catch((err) => console.warn('Could not load app-data.json:', err));

        function safeEvalMath(expr) {
          if (!/^[0-9+\-*/().\s]+$/.test(expr)) {
            throw new Error('Only numbers and + - * / ( ) allowed');
          }
          return Function('"use strict";return (' + expr + ')')();
        }

        function saveAndRender() {
          localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
          itemsList.innerHTML = '';
          entries.forEach((text, index) => {
            const li = document.createElement('li');
            li.className = 'item-row';
            const span = document.createElement('span');
            span.textContent = text;
            const del = document.createElement('button');
            del.className = 'delete-btn';
            del.textContent = 'Remove';
            del.onclick = () => {
              entries.splice(index, 1);
              saveAndRender();
            };
            li.appendChild(span);
            li.appendChild(del);
            itemsList.appendChild(li);
          });
        }

        form.addEventListener('submit', (e) => {
          e.preventDefault();
          const val = input.value.trim();
          if (!val) return;
          if (ARCHETYPE === 'CALCULATOR') {
            try {
              const out = safeEvalMath(val);
              entries.unshift(`${'$'}{val} = ${'$'}{out}`);
              banner.textContent = `Computed: ${'$'}{out}`;
            } catch (err) {
              banner.textContent = `Syntax issue: ${'$'}{err.message}`;
            }
          } else {
            entries.unshift(val);
            banner.textContent = `Saved item #${'$'}{entries.length} locally`;
          }
          input.value = '';
          saveAndRender();
        });

        saveAndRender();
    """.trimIndent() + "\n"

    private fun escapeHtml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun escapeJson(input: String): String =
        input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
}
