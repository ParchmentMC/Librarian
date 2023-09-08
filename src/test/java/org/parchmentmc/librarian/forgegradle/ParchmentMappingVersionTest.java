/*
 * Librarian
 * Copyright (C) 2021 ParchmentMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.parchmentmc.librarian.forgegradle;

import org.gradle.internal.impldep.com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.CheckReturnValue;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ParchmentMappingVersionTest {
    @ParameterizedTest
    @ArgumentsSource(VersionsProvider.class)
    public void testVersionParsesCorrectly(String queryMcVersion, String parchmentVersion, String mcVersion, String mcpTag) {
        final String constructedVersion = hyphenate(queryMcVersion, parchmentVersion, mcVersion, mcpTag);

        final ParchmentMappingVersion version = assertDoesNotThrow(() -> ParchmentMappingVersion.of(constructedVersion));

        // Query MC version -- if input is empty, then it must match MC version
        assertEquals(queryMcVersion.isEmpty() ? mcVersion : queryMcVersion, version.queryMcVersion(), "Query Minecraft version does not match");
        assertEquals(parchmentVersion, version.parchmentVersion(), "Parchment mappings version does not match");
        assertEquals(mcVersion, version.mcVersion(), "Minecraft version does not match");
        // MCP version -- hyphenated combination of MC version and (possibly empty) MCP datetime tag
        assertEquals(hyphenate(mcVersion, mcpTag), version.mcpVersion(), "MCP version does not match");
    }

    public static class VersionsProvider implements ArgumentsProvider {
        private static final List<String> MINECRAFT_VERSIONS = ImmutableList.of(
                "31w41a", "59w26pi_or_tau", // Snapshots (incl. possible April Fools ones)
                "3.1-pre4", "1.5-rc9", "9.2", // Two-component versions
                "3.1.4-pre1", "5.9.2-rc6", "5.3.5", // Three-component versions
                "31.41.59", "265.358.979" // Two-digits and three-digits
        );

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return combinations(
                    // Query Minecraft version, may be empty
                    ImmutableList.<String>builder().addAll(MINECRAFT_VERSIONS).add("").build(),
                    // Parchment version
                    ImmutableList.of(
                            "2001.01.01", "2023.09.06", // Release exports
                            "2001.01.01-nightly-SNAPSHOT", "2023.09.06-nightly-SNAPSHOT", // Nightly exports
                            "BLEEDING-SNAPSHOT" // Bleeding export
                    ),
                    // MC version
                    MINECRAFT_VERSIONS,
                    // MCP version, may be empty; format of "YYMMDD.HHMMSS"
                    ImmutableList.of(
                            "20010101.010101",
                            "20230906.180000",
                            ""
                    )
            ).map(Arguments::of);
        }
    }

    private static String hyphenate(String... strings) {
        return Stream.of(strings).filter(s -> !s.isEmpty()).collect(Collectors.joining("-"));
    }

    @CheckReturnValue
    @SafeVarargs
    private static Stream<String[]> combinations(List<String>... columns) {
        final int columnsCount = columns.length;
        final int lastColIdx = columnsCount - 1;

        final int[] maxIndexes = new int[columnsCount]; // Highest index for each column (stored for quick recall)
        final int[] pointers = new int[columnsCount]; // Pointers to the current index in each column
        pointers[lastColIdx] = -1; // Incremented at the very start, so we do this to not special-case the start 

        long count = 1; // Count of combinations is [size of first col] * [size of second col] * ...
        for (int i = 0; i < columnsCount; i++) {
            final int size = columns[i].size();
            maxIndexes[i] = size - 1;
            count *= size;
        }

        final AbstractSpliterator<String[]> spliterator = new AbstractSpliterator<String[]>(count,
                Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super String[]> action) {
                // We start at the last element and increment backwards, so it's always the last element that changes
                // Think of it like binary counting: 00, 01, 10, 11 (instead of 00, 10, 01, 11) 
                if (increment(lastColIdx)) {
                    final String[] ret = new String[columnsCount];
                    for (int i = 0; i < columnsCount; i++) {
                        ret[i] = columns[i].get(pointers[i]);
                    }
                    action.accept(ret);
                    return true;
                }
                return false;
            }

            private boolean increment(int colIdx) {
                if (++pointers[colIdx] > maxIndexes[colIdx]) {
                    // Exceeded pointer for this element -- reset to 0 and move to previous element
                    pointers[colIdx] = 0;
                    if (colIdx == 0) {
                        // All pointers have been exceeded now
                        return false;
                    }
                    return increment(colIdx - 1);
                }
                return true;
            }
        };

        return StreamSupport.stream(spliterator, false);
    }
}
