// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import java.util.Optional;

/**
 * Finite, backtracking-safe subset of regular expressions for manifest {@code pattern} keywords.
 * Allowed: optional ^ and $, literals, '.', escapes \d \w \s \. \- \/, character classes of literals/ranges,
 * and per-atom quantifiers ? or {n} or {n,m} (m <= 256). Rejected: groups, alternation, * + {n,},
 * back-references, look-around. With no groups, quantifiers cannot nest, and at most
 * {@value #MAX_VARIABLE_ATOMS} variable-width atoms bound the worst case to 256^3 steps per input.
 */
public final class SafePatterns {
    static final int MAX_VARIABLE_ATOMS = 3;
    static final int MAX_BOUND = 256;
    static final int MAX_ATOMS = 64;
    private static final String LITERALS = "_-:/ @,;=%#&'";

    private SafePatterns() {}

    /** Empty when the pattern is in the safe subset, otherwise the reason it is rejected. */
    public static Optional<String> violation(String pattern) {
        if (pattern.isEmpty() || pattern.length() > 200) return Optional.of("length must be 1-200");
        int index = pattern.startsWith("^") ? 1 : 0;
        int end = pattern.endsWith("$") && pattern.length() > index ? pattern.length() - 1 : pattern.length();
        int atoms = 0;
        int variable = 0;
        while (index < end) {
            char c = pattern.charAt(index);
            if (c == '[') {
                int close = classEnd(pattern, index + 1, end);
                if (close < 0) return Optional.of("unsupported or unterminated character class");
                index = close + 1;
            } else if (c == '\\') {
                if (index + 1 >= end || "dws.-/".indexOf(pattern.charAt(index + 1)) < 0) {
                    return Optional.of("unsupported escape");
                }
                index += 2;
            } else if (Character.isLetterOrDigit(c) && c < 128 || LITERALS.indexOf(c) >= 0 || c == '.') {
                index++;
            } else {
                return Optional.of("unsupported construct '" + c + "'");
            }
            if (++atoms > MAX_ATOMS) return Optional.of("too many atoms");
            if (index < end) {
                char q = pattern.charAt(index);
                if (q == '?') {
                    variable++;
                    index++;
                } else if (q == '{') {
                    int close = pattern.indexOf('}', index);
                    if (close < 0 || close >= end) return Optional.of("unterminated quantifier");
                    String[] bounds = pattern.substring(index + 1, close).split(",", -1);
                    if (bounds.length > 2 || bounds[0].isEmpty() || !bounds[0].matches("[0-9]{1,4}")
                            || (bounds.length == 2 && !bounds[1].matches("[0-9]{1,4}"))) {
                        return Optional.of("unbounded or malformed quantifier");
                    }
                    int min = Integer.parseInt(bounds[0]);
                    int max = bounds.length == 2 ? Integer.parseInt(bounds[1]) : min;
                    if (max < min || max > (bounds.length == 2 ? MAX_BOUND : 1000)) {
                        return Optional.of("quantifier bound too large");
                    }
                    if (max != min) variable++;
                    index = close + 1;
                }
                if (index < end && "?*+{".indexOf(pattern.charAt(index)) >= 0) {
                    return Optional.of("stacked quantifier");
                }
            }
            if (variable > MAX_VARIABLE_ATOMS) return Optional.of("too many variable-width atoms");
        }
        return Optional.empty();
    }

    private static int classEnd(String pattern, int from, int end) {
        int index = from;
        if (index < end && pattern.charAt(index) == '^') index++;
        int count = 0;
        while (index < end) {
            char c = pattern.charAt(index);
            if (c == ']' && count > 0) return index;
            if (c == '\\') {
                if (index + 1 >= end || "dws.-/".indexOf(pattern.charAt(index + 1)) < 0) return -1;
                index += 2;
            } else if (c < 128 && (Character.isLetterOrDigit(c) || LITERALS.indexOf(c) >= 0 || c == '.')) {
                index++;
            } else {
                return -1;
            }
            count++;
        }
        return -1;
    }
}
