package au.com.dius.pact.core.matchers

import au.com.dius.pact.core.matchers.util.corresponds
import au.com.dius.pact.core.matchers.util.tails
import au.com.dius.pact.core.model.InvalidPathExpression
import au.com.dius.pact.core.model.PathToken
import au.com.dius.pact.core.model.matchingrules.MatchingRuleGroup
import au.com.dius.pact.core.model.matchingrules.MatchingRules
import au.com.dius.pact.core.model.parsePath
import mu.KLogging
import java.util.Comparator
import java.util.function.Predicate

object Matchers : KLogging() {

  const val PACT_MATCHING_WILDCARD = "pact.matching.wildcard"
  private val intRegex = Regex("\\d+")

  private fun matchesToken(pathElement: String, token: PathToken): Int {
    return when (token) {
      is PathToken.Root -> if (pathElement == "$") 2 else 0
      is PathToken.Field -> if (pathElement == token.name) 2 else 0
      is PathToken.Index -> if (pathElement.matches(intRegex) && token.index == pathElement.toInt()) 2 else 0
      is PathToken.StarIndex -> if (pathElement.matches(intRegex)) 1 else 0
      is PathToken.Star -> 1
      else -> 0
    }
  }

  fun matchesPath(pathExp: String, path: List<String>): Int {
    return try {
      val parseResult = parsePath(pathExp)
      val filter = tails(path.reversed()).filter { l ->
        corresponds(l.reversed(), parseResult) { pathElement, pathToken ->
          matchesToken(pathElement, pathToken) != 0
        }
      }
      if (filter.isNotEmpty()) {
        filter.maxBy { seq -> seq.size }?.size ?: 0
      } else {
        0
      }
    } catch (e: InvalidPathExpression) {
      logger.warn(e) { "Path expression $pathExp is invalid, ignoring" }
      0
    }
  }

  fun calculatePathWeight(pathExp: String, path: List<String>): Int {
    return try {
      val parseResult = parsePath(pathExp)
      path.zip(parseResult).asSequence().map {
          matchesToken(it.first, it.second)
      }.reduce { acc, i -> acc * i }
    } catch (e: InvalidPathExpression) {
      logger.warn(e) { "Path expression $pathExp is invalid, ignoring" }
      0
    }
  }

  fun resolveMatchers(matchers: MatchingRules, category: String, items: List<String>) =
    if (category == "body")
      matchers.rulesForCategory(category).filter(Predicate {
        matchesPath(it, items) > 0
      })
    else if (category == "header" || category == "query")
      matchers.rulesForCategory(category).filter(Predicate {
        items == listOf(it)
      })
    else matchers.rulesForCategory(category)

  @JvmStatic
  fun matcherDefined(category: String, path: List<String>, matchers: MatchingRules?): Boolean =
    if (matchers != null)
      resolveMatchers(matchers, category, path).isNotEmpty()
    else false

  /**
   * Determines if a matcher of the form '.*' exists for the path
   */
  @JvmStatic
  fun wildcardMatcherDefined(path: List<String>, category: String, matchers: MatchingRules?) =
    if (matchers != null) {
      val resolvedMatchers = matchers.rulesForCategory(category).filter(Predicate {
        matchesPath(it, path) == path.size
      })
      resolvedMatchers.matchingRules.keys.any { entry -> entry.endsWith(".*") }
    } else false

  /**
   * If wildcard matching logic is enabled (where keys are ignored and only values are compared)
   */
  @JvmStatic
  fun wildcardMatchingEnabled() = System.getProperty(PACT_MATCHING_WILDCARD)?.trim() == "true"

  @JvmStatic
  fun <M : Mismatch> domatch(
    matchers: MatchingRules,
    category: String,
    path: List<String>,
    expected: Any?,
    actual: Any?,
    mismatchFn: MismatchFactory<M>
  ): List<M> {
    val matcherDef = selectBestMatcher(matchers, category, path)
    return domatch(matcherDef, path, expected, actual, mismatchFn)
  }

  @JvmStatic
  fun selectBestMatcher(matchers: MatchingRules, category: String, path: List<String>): MatchingRuleGroup {
    val matcherCategory = resolveMatchers(matchers, category, path)
    return if (category == "body")
      matcherCategory.maxBy(Comparator { a, b ->
        val weightA = calculatePathWeight(a, path)
        val weightB = calculatePathWeight(b, path)
        when {
          weightA == weightB -> when {
            a.length > b.length -> 1
            a.length < b.length -> -1
            else -> 0
          }
          weightA > weightB -> 1
          else -> -1
        }
      })
    else {
      matcherCategory.matchingRules.values.first()
    }
  }
}
