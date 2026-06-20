package ai.elrond.ai

import ai.elrond.domain.MathDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathDetectorTest {

    @Test
    fun `detects fractions`() {
        assertTrue(MathDetector.isMath("The answer is 1/2"))
        assertTrue(MathDetector.isMath("3 / 4 of the cake"))
    }

    @Test
    fun `detects exponents`() {
        assertTrue(MathDetector.isMath("x^2 + 1"))
        assertTrue(MathDetector.isMath("2^10 = 1024"))
    }

    @Test
    fun `detects arithmetic and equations`() {
        assertTrue(MathDetector.isMath("2 + 3 = 5"))
        assertTrue(MathDetector.isMath("4 × 5"))
        assertTrue(MathDetector.isMath("x = 7"))
    }

    @Test
    fun `detects basic calculus`() {
        assertTrue(MathDetector.isMath("∫ x dx = x^2/2 + C"))
        assertTrue(MathDetector.isMath("the derivative dy/dx"))
    }

    @Test
    fun `does not flag ordinary prose`() {
        assertFalse(MathDetector.isMath("Buy milk and/or bread"))
        assertFalse(MathDetector.isMath("The moon is about 4.5 billion years old."))
        assertFalse(MathDetector.isMath("Remember to call Sarah on Friday"))
        assertFalse(MathDetector.isMath(""))
    }
}
