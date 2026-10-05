import com.squish.app.online.MAX_TERMS
import com.squish.app.online.searchTerms
import kotlin.system.exitProcess

/*
 * The guard between a text field and a query.
 *
 * What someone types goes into a Lucene query beside a licence filter - the
 * clause that keeps the search to music and footage we are allowed to hand
 * people. A term that escaped its parentheses would widen that query, and an
 * operator sent as a bare word is a syntax error the Archive answers with an
 * empty list, which the app reads as "couldn't reach".
 *
 * Small, and the sort of thing nobody runs until it is wrong.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // The ordinary case.
    check(searchTerms("rock") == "rock", "one word came out ${searchTerms("rock")}")
    check(searchTerms("lofi beats") == "lofi AND beats", "two words came out ${searchTerms("lofi beats")}")
    check(searchTerms("  spaced   out  ") == "spaced AND out", "the spaces were not tidied")

    // Case folded, so a typed operator is a word and not an operator.
    check(searchTerms("rock OR jazz") == "rock AND or AND jazz", "OR survived as an operator: ${searchTerms("rock OR jazz")}")
    check(searchTerms("AND") == "and", "AND survived as an operator")
    check(searchTerms("NOT dance") == "not AND dance", "NOT survived as an operator")

    // Nothing that can leave the clause it is put in.
    listOf(
        "a) OR licence:(any", "\"quoted\"", "a*b", "a?b", "a~b", "a^2", "a\\b", "a:b",
        "{a}", "[a]", "a&&b", "a||b", "a!b", "a+b", "a-b", "a/b"
    ).forEach { typed ->
        val out = searchTerms(typed).orEmpty()
        val bad = out.filterNot { it.isLetterOrDigit() || it == ' ' }
        check(bad.isEmpty(), "\"$typed\" came out as \"$out\", which still holds ${bad.toSet()}")
        // And the only word it may join with is AND.
        out.split(" ").filterIndexed { i, _ -> i % 2 == 1 }.forEach {
            check(it == "AND", "\"$typed\" came out as \"$out\", whose joiner is \"$it\"")
        }
    }

    // Nothing left is nothing, not an empty clause.
    listOf("", "   ", "!!!", "()", "\t\n").forEach {
        check(searchTerms(it) == null, "\"$it\" gave ${searchTerms(it)} rather than null")
    }

    // Capped, so a pasted paragraph is not a query.
    val many = (1..40).joinToString(" ") { "w$it" }
    val capped = searchTerms(many).orEmpty()
    check(capped.split(" AND ").size == MAX_TERMS, "a long search gave ${capped.split(" AND ").size} terms, want $MAX_TERMS")
    check(capped.startsWith("w1 AND w2"), "the cap took the wrong end: $capped")

    // Any script, since a search in Malayalam is a search. Written as escapes
    // rather than as the letters themselves: the suites are compiled by a
    // command line that does not say what charset the sources are in, so a
    // non-ASCII literal arrives as question marks and the check fails for a
    // reason that has nothing to do with the code.
    val malayalam = "കല്യാണം"
    check(searchTerms(malayalam) == malayalam, "a Malayalam word was stripped: ${searchTerms(malayalam)}")
    val accented = "música"
    check(searchTerms("$accented 2026") == "$accented AND 2026", "an accent or a digit was stripped")
    // Letting the marks through must not let anything else through: nothing in
    // what comes out may be a character Lucene reads as syntax.
    val syntax = "+-&|!(){}[]^\"~*?:\\/".toSet()
    listOf(malayalam, accented, "a) OR licence:(any", "مرحبا").forEach { typed ->
        val out = searchTerms(typed).orEmpty()
        check(out.none { it in syntax }, "\"$typed\" came out as \"$out\", which holds query syntax")
    }

    println("search terms: what a text field may put inside a query")
    if (problems.isEmpty()) println("PASS - letters, digits and AND, at most $MAX_TERMS of them, and null for nothing")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
