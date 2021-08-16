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

package org.parchmentmc.librarian;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import net.minecraftforge.gradle.common.config.MCPConfigV2;
import net.minecraftforge.gradle.common.util.HashFunction;
import net.minecraftforge.gradle.common.util.HashStore;
import net.minecraftforge.gradle.common.util.MavenArtifactDownloader;
import net.minecraftforge.gradle.common.util.Utils;
import net.minecraftforge.gradle.mcp.ChannelProvider;
import net.minecraftforge.gradle.mcp.MCPRepo;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IRenamer;
import org.gradle.api.Project;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ParchmentChannelProvider implements ChannelProvider {
    protected static final Gson GSON = new Gson();
    protected static final Pattern PARCHMENT_PATTERN = Pattern.compile("(?<mappingsversion>[\\w\\-.]+)-(?<mcpversion>(?<mcversion>[\\d.]+)(?:-\\d{8}\\.\\d{6})?)");
    protected static final Pattern LETTERS_ONLY_PATTERN = Pattern.compile("[a-zA-Z]+");

    @Nonnull
    @Override
    public Set<String> getChannels() {
        return ImmutableSet.of("parchment");
    }

    @Nullable
    @Override
    public File getMappingsFile(MCPRepo mcpRepo, Project project, String channel, String version) throws IOException {
        // Format is {MAPPINGS_VERSION}-{MC_VERSION}-{MCP_VERSION} where MCP_VERSION is optional
        Matcher matcher = PARCHMENT_PATTERN.matcher(version);
        if (!matcher.matches())
            throw new IllegalStateException("Parchment version of " + version + " is invalid");

        String mappingsversion = matcher.group("mappingsversion");
        String mcversion = matcher.group("mcversion");
        String mcpversion = matcher.group("mcpversion");

        File client = MavenArtifactDownloader.generate(project, "net.minecraft:client:" + mcversion + ":mappings@txt", true);
        if (client == null)
            throw new IllegalStateException("Could not create " + mcversion + " official mappings due to missing ProGuard mappings.");

        File mcp = getMCP(project, mcpversion);
        if (mcp == null)
            return null;

        MCPConfigV2 config = MCPConfigV2.getFromArchive(mcp);

        IMappingFile srg = findObfToSrg(project, mcp, config);
        if (srg == null)
            throw new IllegalStateException("Could not create " + mcpversion + " parchment mappings due to missing MCP's tsrg");

        File dep = getParchmentZip(project, mappingsversion, mcversion);

        File mappings = cacheParchment(project, mcpversion, mappingsversion, "zip");
        HashStore cache = new HashStore()
                .load(cacheParchment(project, mcpversion, mappingsversion, "zip.input"))
                .add("mcp", mcp)
                .add("mcversion", version)
                .add("mappings", dep)
                .add("codever", "1");

        if (cache.isSame() && mappings.exists())
            return mappings;

        boolean official = config.isOfficial();
        try (ZipFile zip = new ZipFile(dep)) {
            ZipEntry entry = zip.getEntry("parchment.json");
            if (entry == null)
                throw new IllegalStateException("Parchment zip did not contain \"parchment.json\"");

            JsonObject json = GSON.fromJson(new InputStreamReader(zip.getInputStream(entry)), JsonObject.class);
            String specversion = json.get("version").getAsString();
            if (!specversion.startsWith("1."))
                throw new IllegalStateException("Parchment mappings spec version was " + specversion + " and did not start with \"1.\", cannot parse!");
            IMappingFile mojToObf = IMappingFile.load(client);
            // Have to do it this way to preserve parameters and eliminate SRG classnames
            IMappingFile mojToSrg = srg.reverse().chain(mojToObf.reverse()).reverse().rename(new IRenamer() {
                @Override
                public String rename(IMappingFile.IClass value) {
                    return value.getOriginal();
                }
            });

            String[] header = {"searge", "name", "desc"};
            List<String[]> packages = Lists.<String[]>newArrayList(header);
            List<String[]> classes = Lists.<String[]>newArrayList(header);
            List<String[]> fields = Lists.<String[]>newArrayList(header);
            List<String[]> methods = Lists.<String[]>newArrayList(header);
            List<String[]> parameters = Lists.<String[]>newArrayList(header);

            Map<String, JsonObject> classMap = getNamedJsonMap(json.getAsJsonArray("classes"), false);
            Map<String, JsonObject> packageMap = getNamedJsonMap(json.getAsJsonArray("packages"), false);

            for (IMappingFile.IPackage srgPackage : mojToSrg.getPackages()) {
                JsonObject pckg = packageMap.get(srgPackage.getOriginal());
                populateMappings(packages, null, srgPackage, pckg);
            }
            for (IMappingFile.IClass srgClass : mojToSrg.getClasses()) {
                JsonObject cls = classMap.get(srgClass.getOriginal());
                populateMappings(classes, srgClass, srgClass, cls);

                Map<String, JsonObject> fieldMap = cls == null ? ImmutableMap.of() : getNamedJsonMap(cls.getAsJsonArray("fields"), false);
                for (IMappingFile.IField srgField : srgClass.getFields()) {
                    populateMappings(fields, srgClass, srgField, fieldMap.get(srgField.getOriginal()));
                }

                Map<String, JsonObject> methodMap = cls == null ? ImmutableMap.of() : getNamedJsonMap(cls.getAsJsonArray("methods"), true);
                for (IMappingFile.IMethod srgMethod : srgClass.getMethods()) {
                    JsonObject method = methodMap.get(srgMethod.getOriginal() + srgMethod.getDescriptor());
                    StringBuilder mdJavadoc = new StringBuilder(getJavadocs(method));
                    List<IMappingFile.IParameter> srgParams = new ArrayList<>(srgMethod.getParameters());
                    if (method != null && method.has("parameters")) {
                        JsonArray jsonParams = method.getAsJsonArray("parameters");
                        if (!official || jsonParams.size() == srgParams.size())
                            for (int i = 0; i < jsonParams.size(); i++) {
                                JsonObject parameter = jsonParams.get(i).getAsJsonObject();
                                boolean isConstructor = method.get("name").getAsString().equals("<init>");
                                String srgParam;
                                if (official) {
                                    srgParam = srgParams.get(i).getMapped();
                                } else {
                                    String srgId = srgMethod.getMapped().indexOf('_') == -1
                                            ? srgMethod.getMapped()
                                            : srgMethod.getMapped().split("_")[1];
                                    if (LETTERS_ONLY_PATTERN.matcher(srgId).matches())
                                        continue; // This means it's a mapped parameter of a functional interface method and we can't use it.
                                    srgParam = String.format(isConstructor ? "p_i%s_%s_" : "p_%s_%s_", srgId, parameter.get("index").getAsString());
                                }
                                String paramName = parameter.has("name") ? parameter.get("name").getAsString() : null;
                                if (paramName != null) {
                                    parameters.add(new String[]{srgParam, paramName, ""});
                                }
                                String paramJavadoc = getJavadocs(parameter);
                                if (!paramJavadoc.isEmpty())
                                    mdJavadoc.append("\\n@param ").append(paramName != null ? paramName : srgParam).append(' ').append(paramJavadoc);
                            }
                    }
                    populateMappings(methods, srgClass, srgMethod, mdJavadoc.toString());
                }
            }

            if (!mappings.getParentFile().exists())
                mappings.getParentFile().mkdirs();

            try (FileOutputStream fos = new FileOutputStream(mappings);
                    ZipOutputStream out = new ZipOutputStream(fos)) {
                writeCsv("classes.csv", classes, out);
                writeCsv("fields.csv", fields, out);
                writeCsv("methods.csv", methods, out);
                writeCsv("params.csv", parameters, out);
                writeCsv("packages.csv", packages, out);
            }
        }

        cache.save();
        Utils.updateHash(mappings, HashFunction.SHA1);

        return mappings;
    }

    protected static File getParchmentZip(Project project, String mappingsversion, String mcversion) {
        String artifact = "org.parchmentmc.data:parchment-" + mcversion + ":" + mappingsversion + ":checked@zip";
        File dep = MavenArtifactDownloader.manual(project, artifact, false); // currently breaks - getDependency(project, artifact);
        if (dep == null) {
            // TODO remove this later? or keep backwards-compatibility with older releases?
            dep = MavenArtifactDownloader.manual(project, artifact.replace(":checked", ""), false);
        }
        if (dep == null)
            throw new IllegalStateException("Could not find Parchment version of " + mappingsversion + '-' + mcversion + " with artifact " + artifact);
        return dep;
    }

    // private static Map<Object, File> dependencyCache = new ConcurrentHashMap<>();
    // protected static synchronized File getDependency(Project project, Object dependencyNotation) {
    //     File cached = dependencyCache.get(dependencyNotation);
    //     if (cached != null)
    //         return cached;
    //     try {
    //         Iterator<File> iterator = project.getConfigurations().detachedConfiguration(project.getDependencies().create(dependencyNotation)).resolve().iterator();
    //         File dependency = iterator.hasNext() ? iterator.next() : null;
    //         if (dependency != null)
    //             dependencyCache.put(dependencyNotation, dependency);
    //         return dependency;
    //     } catch (ResolveException e) {
    //         return null;
    //     }
    // }

    protected static Path getCacheBase(Project project) {
        File gradleUserHomeDir = project.getGradle().getGradleUserHomeDir();
        return Paths.get(gradleUserHomeDir.getPath(), "caches", "parchmentgradle");
    }

    protected static File getCache(Project project, String... tail) {
        return Paths.get(getCacheBase(project).toString(), tail).toFile();
    }

    protected static File cacheParchment(Project project, String mcpversion, String mappingsVersion, String ext) {
        return getCache(project, "org", "parchmentmc", "data", "parchment-" + mcpversion, mappingsVersion, "parchment-" + mcpversion + '-' + mappingsVersion + '.' + ext);
    }

    protected static Map<String, JsonObject> getNamedJsonMap(JsonArray array, boolean hasDescriptor) {
        if (array == null || array.size() == 0)
            return ImmutableMap.of();
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonObject.class::cast)
                .collect(Collectors.toMap(j -> {
                    String key = j.get("name").getAsString();
                    if (hasDescriptor)
                        key += j.get("descriptor").getAsString();
                    return key;
                }, Functions.identity()));
    }

    protected static void populateMappings(List<String[]> mappings, IMappingFile.IClass srgClass, IMappingFile.INode srgNode, JsonObject json) {
        populateMappings(mappings, srgClass, srgNode, getJavadocs(json));
    }

    protected static void populateMappings(List<String[]> mappings, IMappingFile.IClass srgClass, IMappingFile.INode srgNode, String desc) {
        if (srgNode instanceof IMappingFile.IPackage || srgNode instanceof IMappingFile.IClass) {
            String name = srgNode.getMapped().replace('/', '.');
            // TODO fix InstallerTools so that we don't have to expand the csv size for no reason
            if (!desc.isEmpty())
                mappings.add(new String[]{name, name, desc});
            return;
        }
        String srgName = srgNode.getMapped();
        String mojName = srgNode.getOriginal();
        boolean isSrg = srgName.startsWith("p_") || srgName.startsWith("func_") || srgName.startsWith("m_") || srgName.startsWith("field_") || srgName.startsWith("f_");
        // If it's not a srg id and has javadocs, we need to add the class to the beginning as it is a special method/field of some kind
        if (!isSrg && !desc.isEmpty() && (srgNode instanceof IMappingFile.IMethod || srgNode instanceof IMappingFile.IField)) {
            srgName = srgClass.getMapped().replace('/', '.') + '#' + srgName;
        }
        // Only add to the mappings list if it is mapped or has javadocs
        if ((isSrg && !srgName.equals(mojName)) || !desc.isEmpty())
            mappings.add(new String[]{srgName, mojName, desc});
    }

    protected static String getJavadocs(JsonObject json) {
        if (json == null)
            return "";
        JsonElement element = json.get("javadoc");
        if (element == null)
            return "";
        if (element instanceof JsonPrimitive)
            return element.getAsString(); // Parameters don't use an array for some reason
        if (!(element instanceof JsonArray))
            return "";
        JsonArray array = (JsonArray) element;
        StringBuilder sb = new StringBuilder();
        int size = array.size();
        for (int i = 0; i < size; i++) {
            sb.append(array.get(i).getAsString());
            if (i != size - 1)
                sb.append("\\n");
        }
        return sb.toString();
    }

    protected static IMappingFile findObfToSrg(Project project, File mcp, MCPConfigV2 config) throws IOException {
        return IMappingFile.load(new ByteArrayInputStream(Utils.getZipData(mcp, config.getData("mappings"))));
    }

    @Nullable
    protected static File getMCP(Project project, String version) {
        return MavenArtifactDownloader.manual(project, "de.oceanlabs.mcp:mcp_config:" + version + "@zip", false);
    }

    protected static void writeCsv(String name, List<String[]> mappings, ZipOutputStream out) throws IOException {
        if (mappings.size() <= 1)
            return;
        out.putNextEntry(Utils.getStableEntry(name));
        try (CsvWriter writer = CsvWriter.builder().lineDelimiter(LineDelimiter.LF).build(new UncloseableOutputStreamWriter(out))) {
            mappings.forEach(writer::writeRow);
        }
        out.closeEntry();
    }

    private static class UncloseableOutputStreamWriter extends OutputStreamWriter {
        private UncloseableOutputStreamWriter(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            super.flush();
        }
    }
}
