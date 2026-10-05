import com.squish.app.online.MAX_TERMS
import com.squish.app.online.searchTerms
import com.squish.app.online.licenceAllowsCutting
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

    // ---- The licence, which decides what is offered as free b-roll ---------
    //
    // It had been left to the query alone - "AND NOT licenseurl:*-nd* AND NOT
    // licenseurl:*-nc*" - which only matches the hyphenated BY-era codes.
    // Creative Commons 1.0 wrote its codes without the "by", so
    // licenses/nd/1.0/, licenses/nc/1.0/ and licenses/nc-sa/1.0/ have no
    // hyphen before the term and all three slipped through: a no-derivatives
    // or non-commercial clip was offered as free to cut into someone's video.
    run {
        val cuttable = listOf(
            "http://creativecommons.org/publicdomain/zero/1.0/",
            "https://creativecommons.org/publicdomain/mark/1.0/",
            "http://creativecommons.org/licenses/by/4.0/",
            "http://creativecommons.org/licenses/by/2.0/",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "http://creativecommons.org/licenses/sa/1.0/",
            "HTTP://CreativeCommons.org/Licenses/BY-SA/3.0/"
        )
        for (url in cuttable) check(licenceAllowsCutting(url), "a cuttable licence was refused: $url")

        val refused = listOf(
            // The three CC 1.0 forms that slipped the query's filter.
            "http://creativecommons.org/licenses/nd/1.0/",
            "http://creativecommons.org/licenses/nc/1.0/",
            "http://creativecommons.org/licenses/nc-sa/1.0/",
            // And the hyphenated ones, which it did catch.
            "http://creativecommons.org/licenses/by-nc/4.0/",
            "http://creativecommons.org/licenses/by-nd/4.0/",
            "http://creativecommons.org/licenses/by-nc-nd/4.0/",
            "http://creativecommons.org/licenses/by-nc-sa/4.0/",
            // A code nobody here has heard of: refused, not waved through.
            "http://creativecommons.org/licenses/gpl/2.0/",
            "http://example.com/some-other-licence",
            "http://creativecommons.org/licenses//4.0/",
            "creativecommons.org",
            "",
            "   "
        )
        for (url in refused) check(!licenceAllowsCutting(url), "a licence that forbids cutting was allowed: $url")
        check(!licenceAllowsCutting(null), "no licence at all was allowed")

        // No term that bans cutting may ever be accepted, however it is spelt
        // or combined - which is the property an allow-list gives and a
        // deny-list of patterns did not.
        val terms = listOf("by", "sa", "nc", "nd")
        for (a in terms) for (b in terms) for (c in terms) {
            val code = listOf(a, b, c).distinct().joinToString("-")
            val url = "http://creativecommons.org/licenses/$code/4.0/"
            val banned = "nc" in code.split('-') || "nd" in code.split('-')
            check(
                licenceAllowsCutting(url) == !banned,
                "licenses/$code/ came back ${licenceAllowsCutting(url)}"
            )
        }
    }

    println("search terms: what a text field may put inside a query")
    if (problems.isEmpty()) println("PASS - letters, digits and AND, at most $MAX_TERMS of them, and null for nothing")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
