package moe.tarsin.indexreader

data class QuerySpec(val terms: List<String>, val excludes: List<String>)

private val tokenRegex = Regex("\"([^\"]*)\"|(\\S+)")

fun parseQuery(raw: String): QuerySpec {
    val terms = mutableListOf<String>()
    val excludes = mutableListOf<String>()
    tokenRegex.findAll(raw).forEach { match ->
        val quoted = match.groupValues[1]
        val plain = match.groupValues[2]
        val token = if (quoted.isNotEmpty()) quoted else plain
        if (token.isBlank()) return@forEach
        if (token.length > 1 && (token.startsWith("-") || token.startsWith("−"))) {
            excludes.add(token.substring(1))
        } else {
            terms.add(token)
        }
    }
    return QuerySpec(terms, excludes)
}
