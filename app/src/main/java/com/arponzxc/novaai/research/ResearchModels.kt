package com.arponzxc.novaai.research

enum class ResearchMode(val label: String, val synthesisGuidance: String) {
    QUICK("Quick", "Provide a crisp 3-5 bullet executive summary comparing key facts."),
    DETAILED("Detailed", "Provide a structured, well-organized synthesis comparing claims, agreements, and nuances across sources."),
    TECHNICAL("Technical", "Focus on technical mechanisms, precise specifications, numbers, limitations, and primary references.")
}

enum class SourceCredibility(val label: String, val rankWeight: Int) {
    HIGH("High Trust", 3),
    MEDIUM("Verified Web", 2),
    LOW("General", 1)
}

data class SourceItem(
    val title: String,
    val url: String,
    val snippet: String,
    val providerName: String = "Web Reference",
    val domain: String = "",
    val credibility: SourceCredibility = SourceCredibility.MEDIUM
)

data class ResearchProviderOutput(
    val providerName: String,
    val success: Boolean,
    val items: List<SourceItem> = emptyList(),
    val errorMessage: String? = null
)

data class ResearchResult(
    val query: String,
    val summary: String,
    val sources: List<SourceItem>,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: String = ResearchMode.DETAILED.label,
    val success: Boolean = true,
    val errorMessage: String = "",
    val aiSynthesized: Boolean = false,
    val providerDiagnostics: List<String> = emptyList()
)

interface SearchProvider {
    val providerName: String
    suspend fun search(query: String, limit: Int = 5): ResearchProviderOutput
}
