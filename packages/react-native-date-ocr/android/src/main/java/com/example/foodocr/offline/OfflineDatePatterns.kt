package com.example.foodocr.offline

import java.util.Locale

object OfflineDatePatterns {
    private const val MONTH_NAME = "(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)"

    private const val FULL_DATE =
        "(?<!\\d)(?:20|19)\\d{6}(?=\\d{1,5}(?:\\D|$)|[A-Z]|$)" +
            "|\\d{4}\\s*[./\\-,:\\uff1a]\\s*\\d{1,2}\\s*[./\\-,:\\uff1a]\\s*\\d{1,2}" +
            "|\\d{1,2}\\s*[./\\-,:\\uff1a]\\s*\\d{1,2}\\s*[./\\-,:\\uff1a]\\s*\\d{4}" +
            "|\\d{1,2}\\s*[./\\-,:\\uff1a]\\s*\\d{1,2}\\s*[./\\-,:\\uff1a]\\s*\\d{2}(?!\\d)" +
            "|\\d{4}\\s*[./\\-]\\s*\\d{4}(?!\\d)" +
            "|\\d{4}\\s+\\d{1,2}\\s+\\d{1,2}" +
            "|\\d{1,2}\\s+\\d{1,2}\\s+\\d{4}" +
            "|\\d{1,2}\\s+\\d{1,2}\\s+\\d{2}(?!\\d)" +
            "|\\d{2,4}\\s*\\u5e74\\s*\\d{1,2}\\s*\\u6708\\s*\\d{1,2}\\s*\\u65e5?" +
            "|\\d{1,2}\\s*\\u65e5\\s*\\d{1,2}\\s*\\u6708\\s*\\d{2,4}\\s*\\u5e74" +
            "|$MONTH_NAME\\s+\\d{1,2}\\s+\\d{2,4}" +
            "|\\d{1,2}[./\\-\\s]*$MONTH_NAME[./\\-\\s]*\\d{2,4}" +
            "|\\d{6}[./\\-]\\d{1,2}(?!\\d)" +
            "|\\d{2,3}\\s*[./\\-]\\s*\\d{1,2}\\s*[./\\-]\\s*\\d{1,4}" +
            "|(?<!\\d)(?:20|19)\\d{6}(?!\\d{2})" +
            "|(?<!\\d)\\d{8}(?!\\d)" +
            "|(?<!\\d)\\d{6}(?!\\d)"

    private const val YEAR_MONTH =
        "\\d{4}\\s*[./\\-,:\\uff1a]\\s*\\d{1,2}(?!\\s*[./\\-,:\\uff1a]?\\d)" +
            "|\\d{1,2}\\s*[./\\-,:\\uff1a]\\s*\\d{4}(?!\\d)" +
            "|\\d{4}\\s+\\d{1,2}(?!\\s+\\d)" +
            "|\\d{1,2}\\s+\\d{4}(?!\\d)" +
            "|\\d{4}\\s*\\u5e74\\s*\\d{1,2}\\s*\\u6708" +
            "|(?<!\\d)\\d{6}(?!\\d)"

    private const val PREFIXED =
        "(?:\\u6709\\u6548|\\u88fd\\u9020|\\u5230\\u671f|\\u8cde\\u5473|\\u751f\\u7523|\\u51fa\\u5ee0|" +
            "MFG|MFD|PROD|PRO|EXP|BBF|BBE|BB|BEST\\s*BEFORE|EXPIRY|DATE|PD|ED|DDM)" +
            "(?:DATE|\\u65e5\\u671f|\\u671f\\u9650|\\u671f|\\u65e5)?" +
            "[\\uff1a:\\s.\\-/]*" +
            "\\d{2}"

    private val datePattern = Regex("(?:$FULL_DATE|$YEAR_MONTH|$PREFIXED)", RegexOption.IGNORE_CASE)
    private val extractPattern = Regex("(?:$FULL_DATE|$YEAR_MONTH)", RegexOption.IGNORE_CASE)

    private val yearMonthCompact = Regex("(\\d{6})[./\\-](\\d{1,2})(?!\\d)")
    private val yearFirstSeparated = Regex(
        "(\\d{3,4})[./\\-,:\\uff1a\\s\\u5e74]+(\\d{1,2})[./\\-,:\\uff1a\\s\\u6708]+(\\d{1,2})",
        RegexOption.IGNORE_CASE,
    )
    private val yearLastSeparated = Regex(
        "(\\d{1,2})[./\\-,:\\uff1a\\s]+(\\d{1,2})[./\\-,:\\uff1a\\s]+((?:19|20)\\d{2})",
        RegexOption.IGNORE_CASE,
    )
    private val twoDigitSeparated = Regex("(?<!\\d)(\\d{1,2})[./\\-,:\\uff1a\\s]+(\\d{1,2})[./\\-,:\\uff1a\\s]+(\\d{2})(?!\\d)")
    private val chineseDayMonthYear = Regex(
        "(\\d{1,2})\\s*\\u65e5\\s*(\\d{1,2})\\s*\\u6708\\s*(\\d{2,4})\\s*\\u5e74",
    )
    private val monthNameFirst = Regex("($MONTH_NAME)\\s+(\\d{1,2})\\s+(\\d{2,4})", RegexOption.IGNORE_CASE)
    private val dayMonthName = Regex("(\\d{1,2})[./\\-\\s]*($MONTH_NAME)[./\\-\\s]*(\\d{2,4})", RegexOption.IGNORE_CASE)
    private val longYearFirstDigits = Regex("(?<!\\d)((?:19|20)\\d{6})(?:\\d{1,5})?(?=\\D|$)")
    private val compactEight = Regex("(?<!\\d)\\d{8}(?!\\d)")
    private val compactSix = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val digitsOnly = Regex("\\D")

    fun isDateText(text: String?): Boolean {
        if (text.isNullOrBlank() || text == "###") return false
        return datePattern.containsMatchIn(text)
    }

    fun extractDate(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return extractPattern.find(text.uppercase(Locale.ROOT))?.value?.trim()
    }

    fun digits(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return digitsOnly.replace(text, "")
    }

    fun normalizeForDisplay(rawDate: String): String {
        val parsed = parse(rawDate) ?: return normalizeYearMonth(rawDate) ?: rawDate.trim()
        return parsed.normalized
    }

    fun sortKey(rawDate: String): Int {
        val parsed = parse(rawDate) ?: return Int.MAX_VALUE
        return parsed.year * 10_000 + parsed.month * 100 + parsed.day
    }

    fun parse(rawDate: String): ParsedDate? {
        val text = rawDate.trim().uppercase(Locale.ROOT)

        yearMonthCompact.find(text)?.let { match ->
            val digits = match.groupValues[1]
            buildDate(
                year = digits.substring(0, 4).toIntOrNull(),
                month = digits.substring(4, 6).toIntOrNull(),
                day = match.groupValues[2].toIntOrNull(),
            )?.let { return it }
        }

        yearFirstSeparated.find(text)?.let { match ->
            val yearRaw = match.groupValues[1]
            val year = expandYear(yearRaw.toIntOrNull(), yearRaw.length)
            buildDate(
                year = year,
                month = match.groupValues[2].toIntOrNull(),
                day = match.groupValues[3].toIntOrNull(),
            )?.let { return it }
        }

        chineseDayMonthYear.find(text)?.let { match ->
            val yearRaw = match.groupValues[3]
            buildDate(
                year = expandYear(yearRaw.toIntOrNull(), yearRaw.length),
                month = match.groupValues[2].toIntOrNull(),
                day = match.groupValues[1].toIntOrNull(),
            )?.let { return it }
        }

        monthNameFirst.find(text)?.let { match ->
            val yearRaw = match.groupValues[3]
            buildDate(
                year = expandYear(yearRaw.toIntOrNull(), yearRaw.length),
                month = monthNumber(match.groupValues[1]),
                day = match.groupValues[2].toIntOrNull(),
            )?.let { return it }
        }

        dayMonthName.find(text)?.let { match ->
            val yearRaw = match.groupValues[3]
            buildDate(
                year = expandYear(yearRaw.toIntOrNull(), yearRaw.length),
                month = monthNumber(match.groupValues[2]),
                day = match.groupValues[1].toIntOrNull(),
            )?.let { return it }
        }

        yearLastSeparated.find(text)?.let { match ->
            val first = match.groupValues[1].toIntOrNull()
            val second = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()
            buildDate(year, second, first)?.let { return it }
            buildDate(year, first, second)?.let { return it }
        }

        twoDigitSeparated.find(text)?.let { match ->
            parseTwoDigitSeparated(
                first = match.groupValues[1].toIntOrNull(),
                second = match.groupValues[2].toIntOrNull(),
                third = match.groupValues[3].toIntOrNull(),
            )?.let { return it }
        }

        longYearFirstDigits.find(text)?.groupValues?.getOrNull(1)?.let { token ->
            parseCompactEight(token)?.let { return it }
        }

        compactEight.find(text)?.value?.let { token ->
            parseCompactEight(token)?.let { return it }
        }

        compactSix.find(text)?.value?.let { token ->
            parseCompactSix(token)?.let { return it }
        }

        return null
    }

    private fun parseTwoDigitSeparated(first: Int?, second: Int?, third: Int?): ParsedDate? {
        if (first == null || second == null || third == null) return null
        val candidates = mutableListOf<ScoredDate>()
        val lastYear = expandTwoDigitYear(third)
        if (lastYear != null && lastYear >= 2020) {
            buildDate(lastYear, second, first)?.let { candidates += ScoredDate(it, 1.2) }
            buildDate(lastYear, first, second)?.let { candidates += ScoredDate(it, 0.85) }
        }
        val firstYear = expandTwoDigitYear(first)
        if (firstYear != null && firstYear >= 2020) {
            buildDate(firstYear, second, third)?.let { candidates += ScoredDate(it, 1.05) }
        }
        return candidates.maxByOrNull { it.score }?.date
    }

    private fun parseCompactEight(token: String): ParsedDate? {
        val candidates = mutableListOf<ScoredDate>()
        val firstFour = token.substring(0, 4).toIntOrNull()
        if (firstFour != null && firstFour in 2000..2100) {
            buildDate(
                year = firstFour,
                month = token.substring(4, 6).toIntOrNull(),
                day = token.substring(6, 8).toIntOrNull(),
            )?.let { candidates += ScoredDate(it, 1.3) }
        }

        val lastFour = token.substring(4, 8).toIntOrNull()
        if (lastFour != null && lastFour in 2000..2100) {
            buildDate(
                year = lastFour,
                month = token.substring(2, 4).toIntOrNull(),
                day = token.substring(0, 2).toIntOrNull(),
            )?.let { candidates += ScoredDate(it, 1.05) }
            buildDate(
                year = lastFour,
                month = token.substring(0, 2).toIntOrNull(),
                day = token.substring(2, 4).toIntOrNull(),
            )?.let { candidates += ScoredDate(it, 0.75) }
        }
        return candidates.maxByOrNull { it.score }?.date
    }

    private fun parseCompactSix(token: String): ParsedDate? {
        val candidates = mutableListOf<ScoredDate>()
        val firstYear = expandTwoDigitYear(token.substring(0, 2).toIntOrNull())
        if (firstYear != null && firstYear >= 2020) {
            buildDate(
                year = firstYear,
                month = token.substring(2, 4).toIntOrNull(),
                day = token.substring(4, 6).toIntOrNull(),
            )?.let { candidates += ScoredDate(it, 1.1) }
        }

        val lastYear = expandTwoDigitYear(token.substring(4, 6).toIntOrNull())
        if (lastYear != null && lastYear >= 2020) {
            buildDate(
                year = lastYear,
                month = token.substring(2, 4).toIntOrNull(),
                day = token.substring(0, 2).toIntOrNull(),
            )?.let { candidates += ScoredDate(it, 0.95) }
            buildDate(
                year = lastYear,
                month = token.substring(0, 2).toIntOrNull(),
                day = token.substring(2, 4).toIntOrNull(),
            )?.let { candidates += ScoredDate(it, 0.7) }
        }
        return candidates.maxByOrNull { it.score }?.date
    }

    private fun normalizeYearMonth(rawDate: String): String? {
        val text = rawDate.trim()
        Regex("(\\d{4})[./\\-\\s](\\d{1,2})(?![./\\-\\s]?\\d)").find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            if (year != null && year in 2000..2100 && month != null && month in 1..12) {
                return String.format(Locale.US, "%04d-%02d", year, month)
            }
        }
        Regex("(\\d{1,2})[./\\-\\s](\\d{4})(?!\\d)").find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val year = match.groupValues[2].toIntOrNull()
            if (year != null && year in 2000..2100 && month != null && month in 1..12) {
                return String.format(Locale.US, "%04d-%02d", year, month)
            }
        }
        return null
    }

    private fun expandYear(value: Int?, length: Int): Int? {
        if (value == null) return null
        return when {
            length == 2 -> expandTwoDigitYear(value)
            length == 3 -> value + 1911
            else -> value
        }
    }

    private fun expandTwoDigitYear(value: Int?): Int? {
        value ?: return null
        return if (value <= 69) 2000 + value else 1900 + value
    }

    private fun monthNumber(name: String): Int? {
        return when (name.take(3).uppercase(Locale.ROOT)) {
            "JAN" -> 1
            "FEB" -> 2
            "MAR" -> 3
            "APR" -> 4
            "MAY" -> 5
            "JUN" -> 6
            "JUL" -> 7
            "AUG" -> 8
            "SEP" -> 9
            "OCT" -> 10
            "NOV" -> 11
            "DEC" -> 12
            else -> null
        }
    }

    private fun buildDate(year: Int?, month: Int?, day: Int?): ParsedDate? {
        if (year == null || month == null || day == null) return null
        if (!isValidDate(year, month, day)) return null
        return ParsedDate(year, month, day)
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) return false
        val maxDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> return false
        }
        return day <= maxDay
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    private data class ScoredDate(
        val date: ParsedDate,
        val score: Double,
    )
}

data class ParsedDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    val normalized: String
        get() = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
}
