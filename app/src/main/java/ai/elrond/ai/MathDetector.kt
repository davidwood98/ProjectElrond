package ai.elrond.ai

/**
 * Heuristic detector for whether an AI response is a mathematical expression
 * worth rendering with [ai.elrond.ui.MathText] rather than plain handwriting.
 *
 * Pure logic so it can be unit-tested. Conservative: prose that merely contains
 * a slash (e.g. "and/or") should not be flagged.
 */
object MathDetector {

    private val FRACTION = Regex("""\b\d+\s*/\s*\d+\b""") // 1/2, 3 / 4
    private val EXPONENT = Regex("""[A-Za-z0-9]\s*\^\s*-?\d""") // x^2, 2^10
    private val EQUATION = Regex("""[A-Za-z0-9)]\s*=\s*[-A-Za-z0-9(]""") // a = 2, x=3
    private val CALCULUS = Regex("""(∫|√|\bd/d[a-z]\b|\bdy/dx\b|\bd[a-z]\b\s*$|\bintegral\b|\bderivative\b)""")
    private val ARITHMETIC = Regex("""\d+\s*[+\-×*÷]\s*\d+""") // 2 + 3, 4 × 5

    fun isMath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return FRACTION.containsMatchIn(t) ||
            EXPONENT.containsMatchIn(t) ||
            CALCULUS.containsMatchIn(t) ||
            ARITHMETIC.containsMatchIn(t) ||
            EQUATION.containsMatchIn(t)
    }
}
