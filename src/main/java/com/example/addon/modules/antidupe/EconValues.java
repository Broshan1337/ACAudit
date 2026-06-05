package com.example.addon.modules.antidupe;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared economy fuzz-value source, grouped into selectable classes.
 *
 * EconFuzz, VaultValueProbe and SignContentFuzz attack the same parsers through
 * three different delivery paths (command / two-account transfer / sign text), so
 * the dangerous-value lists live here once instead of being copy-pasted -- add a
 * new value in one place and every delivery path picks it up.
 *
 * Values are grouped by Category so an auditor can fuzz ONE class at a time
 * (scientific notation, integer/long boundaries, special floats, ...) instead of
 * waiting through every value. EconFuzz exposes the Category as a "value-set" setting.
 *
 * All entries are CHAR-FILTER-SAFE: nothing below 0x20, 0x7F, or the section sign,
 * so the value actually REACHES the plugin parser rather than tripping Minecraft's
 * chat/command character filter (which would test the server, not the plugin).
 * Non-ASCII is written as \\u escapes so this file stays plain ASCII.
 */
public final class EconValues {
    private EconValues() {}

    public enum Category {
        ALL, BASELINE, NEGATIVES, NEGATIVE_ZERO, DECIMALS_PRECISION, SCIENTIFIC,
        BIG_SCIENTIFIC, INTEGER_LONG, SPECIAL_FLOATS, FORMATTING, LOCALE,
        BROKEN_SEPARATORS, LEADING_ZEROS, CURRENCY, WHITESPACE, UNICODE_DIGITS,
        FORMAT_STRING, PERCENT_ENCODED, SQL_INJECTION, VERY_LONG,
        // Curated delivery subsets (default for their module; selectable from any).
        TRANSFER, SIGN
    }

    public static final String[] BASELINE = { "0", "1", "64" };

    public static final String[] NEGATIVES = { "-1", "-64", "-1000000" };

    public static final String[] NEGATIVE_ZERO = { "-0", "-0.0", "-0.00", "0.0", "-0E0", "0e-0", "-0e-0" };

    public static final String[] DECIMALS_PRECISION = {
        "0.0001", "1.5", "0.1", "0.999999999999",
        "0." + "1".repeat(198),               // absurd BigDecimal scale (char-capped)
        "1.0000000000000002",                 // catastrophic-cancellation bait
        "0.30000000000000004",                // 0.1+0.2 result
        "9007199254740993",                   // 2^53+1: unrepresentable as double
        "4503599627370497",                   // 2^52+1: precision boundary
        "9999999999999999",                   // 16 nines: rounds when stored as double
        "1.7976931348623157",                 // Double.MAX mantissa
        "1.5000", "1.50000000",               // trailing zeros some parsers mishandle
    };

    public static final String[] SCIENTIFIC = {
        "1e6", "1E6", "1e06", "-1e6", "1e-6", "1.5e3", "1e+342432423",
        "1e+6", "1E+6", "1.0e6", "1.0E-6",
        "1e308", "1e-308", "5e-324",          // double max-ish / smallest positive / denormal
        "1.7976931348623157E308",             // exactly Double.MAX_VALUE
        "4.9E-324",                           // exactly Double.MIN_VALUE (denormal)
        "2.2250738585072014E-308",            // smallest normal double
        "0E0", "1e0", "1e00",
        // malformed exponents that lenient parsers may still chew on
        "1e", "e5", "1e+", "1e-", "1E", "1e1e1", "1ee9", "1.e9", ".1e9",
    };

    /**
     * BIG scientific notation: the "make a no-validation plugin go crazy" set.
     * Double overflow -> Infinity (infinite balance), underflow -> 0 (free), and
     * BigDecimal scale explosions (huge allocation / ArithmeticException / hang).
     */
    public static final String[] BIG_SCIENTIFIC = {
        "1e309", "1e310", "1e400", "1.0e400", "1e1000",     // -> Double.POSITIVE_INFINITY
        "9e999", "9e9999", "9e999999999",                   // -> Infinity, big exponent parse
        "1e-400", "1e-1000", "1e-999999999",                // -> 0.0 ("buy for free")
        "1E+999", "1E-999",
        "1E+2147483647", "1E-2147483647",                   // BigDecimal scale at Integer.MAX
        "1e2147483648", "1e-2147483648",                    // exponent overflows int
        "1e9999999999999999999",                            // exponent overflows long
        "9".repeat(120) + "e99",                            // huge mantissa + exponent (char-capped)
    };

    public static final String[] INTEGER_LONG = {
        String.valueOf(Integer.MAX_VALUE), String.valueOf(Integer.MIN_VALUE),
        "2147483648", "2147483649", "-2147483649",          // int overflow by 1, 2
        "4294967295", "4294967296",                         // 2^32-1, 2^32 (unsigned int boundary)
        String.valueOf(Long.MAX_VALUE), String.valueOf(Long.MIN_VALUE),
        "9223372036854775808", "-9223372036854775809",      // long overflow by 1
        "18446744073709551615", "18446744073709551616",     // 2^64-1, 2^64 (unsigned long boundary)
        "100000000000000000000000000000",                   // far beyond long
    };

    public static final String[] SPECIAL_FLOATS = {
        "NaN", "Infinity", "-Infinity", "+Infinity",
        "NAN", "INFINITY", "nan", "infinity", "-infinity",
        "Inf", "inf", "+Inf", "-inf",                       // lenient-parser spellings
    };

    public static final String[] FORMATTING = {
        "+100", "1,000", "1.000.000", "1_000", "1_0_0", "0_0", "1__000",
        "0x10", "0x1p4", "0b1010", "010",                   // hex / hex-float / binary / octal-ish
        "1f", "1d", "1L", "1.5f", "1.5d", "100D", "100F",   // Java numeric suffixes
        "+-100", "--100", "++100", "100%", "1/2", ".5", "5.", "0.",
        "1'000",                                            // Swiss apostrophe thousands
        "1\u00A0000", "1\u202F000", "1\u2009000",           // NBSP / narrow-NBSP / thin-space thousands
    };

    public static final String[] LOCALE = {
        "1,5", "1.000,00", "1 000,00", "1,50", "1.234.567,89", "1\u00A0000,00",
    };

    public static final String[] BROKEN_SEPARATORS = {
        "1..0", "1.,0", ".100", "100.", "1.2.3", "1,,0", "..1", ",1", "1.", ".",
    };

    public static final String[] LEADING_ZEROS = { "0100", "00064", "007", "0000000001", "000", "0x00" };

    public static final String[] CURRENCY = {
        "$100", "\u20AC100", "\u00A3100", "\u00A5100", "\u20BD100",   // $ EUR GBP JPY RUB
        "$-100", "-$100", "100$", "\u20AC-1", "USD100", "100 USD",
    };

    public static final String[] WHITESPACE = {
        "100 ", " 100", " 100 ", "1 00", "\u00A0100", "100\u00A0", "\u3000100", " 1 0 0 ",
    };

    /**
     * Unicode digits: look numeric to a human, are NOT ASCII '0'-'9'. Integer.parse
     * via Character.digit accepts the Nd-category scripts; Double.parseDouble rejects
     * them -- so the command parser and the economy API can DISAGREE on the same input.
     * The No-category ones (superscript/subscript/circled/fractions) and the invisible
     * ones (zero-width / RLO) test sanitization rather than parse divergence.
     */
    public static final String[] UNICODE_DIGITS = {
        "\uFF11\uFF10\uFF10",   // fullwidth 100
        "\u0661\u0660\u0660",   // Arabic-Indic 100
        "\u06F1\u06F0\u06F0",   // Extended Arabic-Indic (Persian) 100
        "\u0967\u0968\u0969",   // Devanagari 123
        "\u09E7\u09E6\u09E6",   // Bengali 100
        "\u0E51\u0E50\u0E50",   // Thai 100
        "\u0660\u0660\u0661",   // Arabic-Indic 001
        "1\u06F00",               // mixed ASCII + Persian zero
        "\uFF11\u066000",        // mixed fullwidth + Arabic
        "\u00B9\u00B2\u00B3",   // superscript 123 (No)
        "\u2081\u2080\u2080",   // subscript 100 (No)
        "\u2460\u2461\u2462",   // circled 1 2 3 (No)
        "\u2474",                 // parenthesized 1 (No)
        "\u00BD", "\u2155",      // fraction 1/2, 1/5
        "\u202E001",              // RLO: displays as 100, parses as 001
        "\u200B100",              // zero-width space prefix
        "1\u200B00",              // zero-width space inside
        "\u200E100",              // LTR mark prefix
    };

    public static final String[] FORMAT_STRING = {
        "%d", "%s", "%n", "%f", "%%", "%100d", "%1$s", "{0}", "{amount}",
        "${amount}", "#{amount}", "{}", "{{100}}",
        "${7*7}", "#{7*7}", "{{7*7}}",                      // template/EL injection
        "%.99999f", "%2147483647d",                         // String.format width DoS
    };

    public static final String[] PERCENT_ENCODED = {
        "%31%30%30", "%2B100", "%2D1", "%00", "%0a100", "%2e5",
    };

    public static final String[] SQL_INJECTION = {
        "1; DROP TABLE players;--", "1' OR '1'='1", "1 OR 1=1",
        "1; SELECT * FROM balances;--", "1\"; DROP TABLE economy;--",
    };

    public static final String[] VERY_LONG = {
        "9".repeat(200), "1" + "0".repeat(199), "-" + "9".repeat(199), "9".repeat(150) + "e9",
    };

    /** Every class concatenated -- the broadest single-account parser fuzz. */
    public static final String[] FULL = concat(
        BASELINE, NEGATIVES, NEGATIVE_ZERO, DECIMALS_PRECISION, SCIENTIFIC, BIG_SCIENTIFIC,
        INTEGER_LONG, SPECIAL_FLOATS, FORMATTING, LOCALE, BROKEN_SEPARATORS, LEADING_ZEROS,
        CURRENCY, WHITESPACE, UNICODE_DIGITS, FORMAT_STRING, PERCENT_ENCODED, SQL_INJECTION, VERY_LONG);

    /** Resolve a Category (ALL -> FULL) to its value array. */
    public static String[] forCategory(Category c) {
        return switch (c) {
            case ALL -> FULL;
            case BASELINE -> BASELINE;
            case NEGATIVES -> NEGATIVES;
            case NEGATIVE_ZERO -> NEGATIVE_ZERO;
            case DECIMALS_PRECISION -> DECIMALS_PRECISION;
            case SCIENTIFIC -> SCIENTIFIC;
            case BIG_SCIENTIFIC -> BIG_SCIENTIFIC;
            case INTEGER_LONG -> INTEGER_LONG;
            case SPECIAL_FLOATS -> SPECIAL_FLOATS;
            case FORMATTING -> FORMATTING;
            case LOCALE -> LOCALE;
            case BROKEN_SEPARATORS -> BROKEN_SEPARATORS;
            case LEADING_ZEROS -> LEADING_ZEROS;
            case CURRENCY -> CURRENCY;
            case WHITESPACE -> WHITESPACE;
            case UNICODE_DIGITS -> UNICODE_DIGITS;
            case FORMAT_STRING -> FORMAT_STRING;
            case PERCENT_ENCODED -> PERCENT_ENCODED;
            case SQL_INJECTION -> SQL_INJECTION;
            case VERY_LONG -> VERY_LONG;
            case TRANSFER -> TRANSFER;
            case SIGN -> SIGN;
        };
    }

    private static String[] concat(String[]... arrays) {
        List<String> out = new ArrayList<>();
        for (String[] a : arrays) for (String s : a) out.add(s);
        return out.toArray(new String[0]);
    }

    // -- Delivery-path subsets (unchanged) ------------------------------------

    /** Transfer set -- amounts that break a two-leg withdraw/deposit invariant (used by vault-value-probe). */
    public static final String[] TRANSFER = {
        "0", "-1", "-1000000",            // negative transfer = reverse-steal
        "NaN", "Infinity", "-Infinity",   // non-finite poison one leg
        "1e308", "1e309", "1e1000",       // overflow to Infinity on deposit
        "1e-308", "1e-1000", "5e-324",    // underflow to 0 on withdraw ("free" transfer)
        "9007199254740993",               // 2^53+1 precision loss across the two doubles
        "0.005", "0.001",                 // sub-cent rounding asymmetry between legs
        "9223372036854775807",            // Long.MAX as double
        "1,5", "1.000,00",                // locale-comma reinterpretation mid-transfer
    };

    /** Sign set -- dangerous values short enough for sign lines (used by sign-content-fuzz). */
    public static final String[] SIGN = {
        "-1", "0", "NaN", "Infinity", "1e308", "1e1000", "1e-1000",
        "9007199254740993", "1,5", "1.000,00", "0.001", "%d", "${amount}",
        "999999999999999", "0x64", "  100  ", "9".repeat(60),
    };
}
