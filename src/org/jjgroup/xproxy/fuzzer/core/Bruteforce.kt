package org.jjgroup.xproxy.fuzzer.core

class Bruteforce {
    companion object {
        private val digits = charArrayOf(
            '0', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
            'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q',
            'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
        )

        @JvmStatic
        fun generate(seed: Int, count: Int, accumulator: MutableList<String>): Int {
            var num = seed
            var limit = seed + count
            while (num < limit) {
                val word = numToWord(num)
                if (word != null) {
                    accumulator.add(word)
                } else {
                    limit++
                }
                num++
            }
            return num
        }

        private fun numToWord(num: Int): String? {
            val number = numToString(num)
            return number.takeIf { !it.contains("0") }
        }

        private fun numToString(value: Int): String {
            if (value < 0) {
                throw IllegalArgumentException("+ve integers only please")
            }

            var i = -value
            val buffer = CharArray(7)
            var charPos = 6

            while (i <= -digits.size) {
                buffer[charPos--] = digits[-(i % digits.size)]
                i /= digits.size
            }
            buffer[charPos] = digits[-i]

            return String(buffer, charPos, 7 - charPos)
        }
    }
}
