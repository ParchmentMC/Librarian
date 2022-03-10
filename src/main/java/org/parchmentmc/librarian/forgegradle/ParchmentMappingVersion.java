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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParchmentMappingVersion {
    public static final Pattern PARCHMENT_PATTERN = Pattern.compile("(?:(?<querymcversion>1\\.[\\d.]+)-)?(?<mappingsversion>[\\w\\-.]+?)-(?<mcpversion>(?<mcversion>[\\d.]+)(?:-\\d{8}\\.\\d{6})?)");
    private final String queryMcVersion;
    private final String parchmentVersion;
    private final String mcVersion;
    private final String mcpVersion;

    public ParchmentMappingVersion(@Nullable String queryMcVersion, String parchmentVersion, String mcVersion, String mcpVersion) {
        this.queryMcVersion = queryMcVersion == null || queryMcVersion.isEmpty() ? mcVersion : queryMcVersion;
        this.parchmentVersion = parchmentVersion;
        this.mcVersion = mcVersion;
        this.mcpVersion = mcpVersion;
    }

    public static ParchmentMappingVersion of(String version) {
        // Format is {QUERY_MC_VERSION}-{MAPPINGS_VERSION}-{MC_VERSION}-{MCP_VERSION} where QUERY_MC_VERSION and MCP_VERSION are optional
        Matcher matcher = PARCHMENT_PATTERN.matcher(version);
        if (!matcher.matches())
            throw new IllegalStateException("Parchment version of " + version + " is invalid");

        return new ParchmentMappingVersion(matcher.group("querymcversion"), matcher.group("mappingsversion"), matcher.group("mcversion"), matcher.group("mcpversion"));
    }

    @Nonnull
    public String queryMcVersion() {
        return queryMcVersion;
    }

    @Nonnull
    public String parchmentVersion() {
        return parchmentVersion;
    }

    @Nonnull
    public String mcVersion() {
        return mcVersion;
    }

    @Nonnull
    public String mcpVersion() {
        return mcpVersion;
    }

    @Override
    public String toString() {
        String prefix = queryMcVersion.equals(mcVersion) ? "" : queryMcVersion + "-";
        return prefix + parchmentVersion + "-" + mcpVersion;
    }
}
